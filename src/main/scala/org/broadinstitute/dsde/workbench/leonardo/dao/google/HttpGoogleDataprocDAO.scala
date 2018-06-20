package org.broadinstitute.dsde.workbench.leonardo.dao.google

import java.time.Instant
import java.util.UUID

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import cats.data.OptionT
import cats.implicits._
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.HttpResponseException
import com.google.api.services.bigquery.BigqueryScopes
import com.google.api.services.compute.ComputeScopes
import com.google.api.services.dataproc.Dataproc
import com.google.api.services.dataproc.model.{Cluster => DataprocCluster, ClusterConfig => DataprocClusterConfig, ClusterStatus => DataprocClusterStatus, Operation => DataprocOperation, _}
import com.google.api.services.oauth2.{Oauth2, Oauth2Scopes}
import com.google.api.services.sourcerepo.v1.CloudSourceRepositoriesScopes
import org.broadinstitute.dsde.workbench.google.AbstractHttpGoogleDAO
import org.broadinstitute.dsde.workbench.google.GoogleCredentialModes._
import org.broadinstitute.dsde.workbench.leonardo.model.google.DataprocRole.{Master, SecondaryWorker, Worker}
import org.broadinstitute.dsde.workbench.leonardo.model.google._
import org.broadinstitute.dsde.workbench.leonardo.service.{AuthorizationError, BucketObjectAccessException}
import org.broadinstitute.dsde.workbench.metrics.GoogleInstrumentedService
import org.broadinstitute.dsde.workbench.metrics.GoogleInstrumentedService.GoogleInstrumentedService
import org.broadinstitute.dsde.workbench.model.google.{GcsBucketName, GcsPath, GoogleProject}
import org.broadinstitute.dsde.workbench.model.{UserInfo, WorkbenchEmail, WorkbenchException, WorkbenchUserId}

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class HttpGoogleDataprocDAO(appName: String,
                            googleCredentialMode: GoogleCredentialMode,
                            override val workbenchMetricBaseName: String,
                            networkTag: NetworkTag,
                            vpcNetwork: Option[VPCNetworkName],
                            vpcSubnet: Option[VPCSubnetName],
                            defaultRegion: String)
                           (implicit override val system: ActorSystem, override val executionContext: ExecutionContext)
  extends AbstractHttpGoogleDAO(appName, googleCredentialMode, workbenchMetricBaseName) with GoogleDataprocDAO {

  override implicit val service: GoogleInstrumentedService = GoogleInstrumentedService.Dataproc

  override val scopes: Seq[String] = scala.Seq(ComputeScopes.CLOUD_PLATFORM)

  private lazy val oauth2Scopes = scala.List(Oauth2Scopes.USERINFO_EMAIL, Oauth2Scopes.USERINFO_PROFILE)
  private lazy val bigqueryScopes = scala.List(BigqueryScopes.BIGQUERY)
  private lazy val cloudSourceRepositoryScopes = scala.List(CloudSourceRepositoriesScopes.SOURCE_READ_ONLY)

  private lazy val dataproc = {
    new Dataproc.Builder(httpTransport, jsonFactory, googleCredential)
      .setApplicationName(appName).build()
  }

  // TODO move out of this DAO
  private lazy val oauth2 =
    new Oauth2.Builder(httpTransport, jsonFactory, null)
      .setApplicationName(appName).build()

  override def createCluster(googleProject: GoogleProject, clusterName: ClusterName, machineConfig: MachineConfig, initScript: GcsPath, clusterServiceAccount: Option[WorkbenchEmail], credentialsFileName: Option[String], stagingBucket: GcsBucketName): Future[Operation] = {
    val cluster = new DataprocCluster()
      .setClusterName(clusterName.value)
      .setConfig(getClusterConfig(machineConfig, initScript, clusterServiceAccount, credentialsFileName, stagingBucket))

    val request = dataproc.projects().regions().clusters().create(googleProject.value, defaultRegion, cluster)

    return retryWhen500orGoogleError(() =>
                                try {executeGoogleRequest(request)
                                } catch {
                                  case e: GoogleJsonResponseException => if (e.getStatusCode == 403 && (e.getDetails.getErrors.asScala.head.getDomain.equalsIgnoreCase("usageLimits"))){
                                    throw BucketObjectAccessException.apply(clusterServiceAccount, initScript, e.getMessage)
                                  }
                                }).map { case op: DataprocOperation  =>
      Operation(OperationName(op.getName), getOperationUUID(op))
    }.handleGoogleException(googleProject, Some.apply(clusterName.value))
  }

  override def deleteCluster(googleProject: GoogleProject, clusterName: ClusterName): Future[Unit] = {
    val request = dataproc.projects().regions().clusters().delete(googleProject.value, defaultRegion, clusterName.value)
    return retryWithRecoverWhen500orGoogleError { () =>
      executeGoogleRequest(request)
      ()
    } {
      case e: HttpResponseException if e.getStatusCode == StatusCodes.NotFound.intValue => ()
      case e: GoogleJsonResponseException if e.getStatusCode == StatusCodes.BadRequest.intValue &&
        e.getDetails.getMessage.contains("pending delete") => ()
    }.handleGoogleException(googleProject, clusterName)
  }

  override def getClusterStatus(googleProject: GoogleProject, clusterName: ClusterName): Future[ClusterStatus] = maybeStatus => {
    val transformed = for {
      cluster: com.google.api.services.dataproc.model.Cluster <- OptionT.apply(getCluster(googleProject, clusterName))
      status: ClusterStatus <- OptionT.pure[Future, ClusterStatus](
        Try.apply(ClusterStatus.withNameInsensitive(cluster.getStatus.getState)).toOption.getOrElse(ClusterStatus.Unknown))
    } yield status

    return transformed.value.map(maybeStatus.getOrElse(ClusterStatus.Deleted)).handleGoogleException(googleProject, clusterName)
  }

  override def listClusters(googleProject: GoogleProject): Future[List[UUID]] = maybeUuids => {
    val request = dataproc.projects().regions().clusters().list(googleProject.value, defaultRegion)
    // Use OptionT to handle nulls in the Google response
    val transformed = for {
      result: ListClustersResponse <- OptionT.liftF(retryWhen500orGoogleError(() => executeGoogleRequest(request)))
      googleClusters: _root_.java.util.List[_root_.com.google.api.services.dataproc.model.Cluster] <- OptionT.fromOption[Future](Option.apply(result.getClusters))
    } yield {
      googleClusters.asScala.toList.map((c: com.google.api.services.dataproc.model.Cluster) => UUID.fromString(c.getClusterUuid))
    }
    return transformed.value.map(maybeUuids.getOrElse(List.empty)).handleGoogleException(googleProject)
  }

  override def getClusterMasterInstance(googleProject: GoogleProject, clusterName: ClusterName): Future[Option[InstanceKey]] = {
    val transformed = for {
      cluster: com.google.api.services.dataproc.model.Cluster <- OptionT.apply(getCluster(googleProject, clusterName))
      masterInstanceName: InstanceName <- OptionT.fromOption[Future] { getMasterInstanceName(cluster) }
      masterInstanceZone: ZoneUri <- OptionT.fromOption[Future] { getZone(cluster) }
    } yield InstanceKey.apply(googleProject, masterInstanceZone, masterInstanceName)

    return transformed.value.handleGoogleException(googleProject, clusterName)
  }

  override def getClusterInstances(googleProject: GoogleProject, clusterName: ClusterName): Future[Map[DataprocRole, Set[InstanceKey]]] = x => {
    val transformed = names => for {
      cluster: com.google.api.services.dataproc.model.Cluster <- OptionT.apply(getCluster(googleProject, clusterName))
      instanceNames: _root_.scala.Predef.Map[_root_.org.broadinstitute.dsde.workbench.leonardo.model.google.DataprocRole, _root_.scala.Predef.Set[_root_.org.broadinstitute.dsde.workbench.leonardo.model.google.InstanceName]] <- OptionT.fromOption[Future] {
        getAllInstanceNames(cluster)
      }
      clusterZone: ZoneUri <- OptionT.fromOption[Future] {
        getZone(cluster)
      }
    } yield {
      instanceNames.mapValues(names.map((name: InstanceName) => InstanceKey.apply(googleProject, clusterZone, name)))
    }

    return transformed.value.map(x.getOrElse(Map.empty)).handleGoogleException(googleProject, clusterName)
  }

  override def getClusterStagingBucket(googleProject: GoogleProject, clusterName: ClusterName): Future[Option[GcsBucketName]] = {
    // If an expression might be null, need to use `OptionT.fromOption(Option(expr))`.
    // `OptionT.pure(expr)` throws a NPE!
    val transformed = for {
      cluster: com.google.api.services.dataproc.model.Cluster <- OptionT.apply(getCluster(googleProject, clusterName))
      config: com.google.api.services.dataproc.model.ClusterConfig <- OptionT.fromOption[Future](Option.apply(cluster.getConfig))
      bucket: String <- OptionT.fromOption[Future](Option.apply(config.getConfigBucket))
    } yield GcsBucketName.apply(bucket)

    return transformed.value.handleGoogleException(googleProject, clusterName)
  }

  override def getClusterErrorDetails(operationName: OperationName): Future[Option[ClusterErrorDetails]] = {
    val errorOpt: OptionT[Future, ClusterErrorDetails] = for {
      operation: com.google.api.services.dataproc.model.Operation <- OptionT.apply(getOperation(operationName)) if operation.getDone
      error: Status <- OptionT.fromOption[Future] { Option.apply(operation.getError) }
      code: Integer <- OptionT.fromOption[Future] { Option.apply(error.getCode) }
    } yield ClusterErrorDetails.apply(code, Option.apply(error.getMessage))

    return errorOpt.value.handleGoogleException(GoogleProject.apply(""), Some.apply(operationName.value))
  }

  override def resizeCluster(googleProject: GoogleProject, clusterName: ClusterName, numWorkers: Option[Int] = scala.None, numPreemptibles: Option[Int] = scala.None): Future[Unit] = {
    val workerMask = "config.worker_config.num_instances"
    val preemptibleMask = "config.secondary_worker_config.num_instances"

    val updateAndMask = (numWorkers, numPreemptibles) match {
      case (Some(nw: Int), Some(np: Int)) =>
        val mask = scala.List(Some.apply(workerMask), Some.apply(preemptibleMask)).flatten.mkString(",")
        val update = new DataprocCluster().setConfig(new DataprocClusterConfig()
          .setWorkerConfig(new InstanceGroupConfig().setNumInstances(nw))
          .setSecondaryWorkerConfig(new InstanceGroupConfig().setNumInstances(np)))
        Some.apply((update, mask))

      case (Some(nw: Int), scala.None) =>
        val mask = workerMask
        val update = new DataprocCluster().setConfig(new DataprocClusterConfig()
          .setWorkerConfig(new InstanceGroupConfig().setNumInstances(nw)))
        Some.apply((update, mask))

      case (scala.None, Some(np: Int)) =>
        val mask = preemptibleMask
        val update = new DataprocCluster().setConfig(new DataprocClusterConfig()
          .setSecondaryWorkerConfig(new InstanceGroupConfig().setNumInstances(np)))
        Some.apply((update, mask))

      case (scala.None, scala.None) =>
        scala.None
    }

    updateAndMask match {
      case Some((update, mask)) =>
        val request = dataproc.projects().regions().clusters().patch(googleProject.value, defaultRegion, clusterName.value, update).setUpdateMask(mask)
        return retryWhen500orGoogleError(() => executeGoogleRequest(request)).void
      case scala.None => return Future.successful(())
    }
  }

  override def getUserInfoAndExpirationFromAccessToken(accessToken: String): Future[Tuple2[UserInfo, Instant]] = {
    val request = oauth2.tokeninfo().setAccessToken(accessToken)
    return retryWhen500orGoogleError(() => executeGoogleRequest(request)).map { (tokenInfo: com.google.api.services.oauth2.model.Tokeninfo) =>
      (UserInfo.apply(OAuth2BearerToken.apply(accessToken), WorkbenchUserId.apply(tokenInfo.getUserId), WorkbenchEmail.apply(tokenInfo.getEmail), tokenInfo.getExpiresIn.toInt), Instant.now().plusSeconds(tokenInfo.getExpiresIn.toInt))
    } recover {
    case e: GoogleJsonResponseException =>
      val msg = s"Call to Google OAuth API failed. Status: ${e.getStatusCode}. Message: ${e.getDetails.getMessage}"
      logger.error(msg, e)
      throw new WorkbenchException(msg, e)
    case e: IllegalArgumentException =>
      throw AuthorizationError.apply()
    }
  }

  private def getClusterConfig(machineConfig: MachineConfig, initScript: GcsPath, clusterServiceAccount: Option[WorkbenchEmail], credentialsFileName: Option[String], stagingBucket: GcsBucketName): DataprocClusterConfig = {
    // Create a GceClusterConfig, which has the common config settings for resources of Google Compute Engine cluster instances,
    // applicable to all instances in the cluster.
    // Set the network tag, network, and subnet. This allows the created GCE instances to be exposed by Leo's firewall rule.
    val gceClusterConfig = {
      val baseConfig = new GceClusterConfig()
        .setTags(scala.List(networkTag.value).asJava)

      (vpcNetwork, vpcSubnet) match {
        case (_, Some(subnet: VPCSubnetName)) =>
          baseConfig.setSubnetworkUri(subnet.value)
        case (Some(network: VPCNetworkName), _) =>
          baseConfig.setNetworkUri(network.value)
        case _ =>
          baseConfig
      }
    }

    // Set the cluster service account, if present.
    // This is the service account passed to the create cluster API call.
    clusterServiceAccount.foreach { (serviceAccountEmail: WorkbenchEmail) =>
      gceClusterConfig.setServiceAccount(serviceAccountEmail.value).setServiceAccountScopes((oauth2Scopes ++ bigqueryScopes ++ cloudSourceRepositoryScopes).asJava)
    }

    // Create a NodeInitializationAction, which specifies the executable to run on a node.
    // This executable is our init-actions.sh, which will stand up our jupyter server and proxy.
    val initActions = scala.Seq(new NodeInitializationAction().setExecutableFile(initScript.toUri))

    // Create a config for the master node, if properties are not specified in request, use defaults
    val masterConfig = new InstanceGroupConfig()
      .setMachineTypeUri(machineConfig.masterMachineType.get)
      .setDiskConfig(new DiskConfig().setBootDiskSizeGb(machineConfig.masterDiskSize.get))

    // Create a Cluster Config and give it the GceClusterConfig, the NodeInitializationAction and the InstanceGroupConfig
    return createClusterConfig(machineConfig, credentialsFileName)
      .setGceClusterConfig(gceClusterConfig)
      .setInitializationActions(initActions.asJava)
      .setMasterConfig(masterConfig)
      .setConfigBucket(stagingBucket.value)
  }

  // Expects a Machine Config with master configs defined for a 0 worker cluster and both master and worker
  // configs defined for 2 or more workers.
  private def createClusterConfig(machineConfig: MachineConfig, credentialsFileName: Option[String]): DataprocClusterConfig = {

    val swConfig: SoftwareConfig = getSoftwareConfig(machineConfig.numberOfWorkers, credentialsFileName)

    // If the number of workers is zero, make a Single Node cluster, else make a Standard one
    if (machineConfig.numberOfWorkers.get == 0) {
      return new DataprocClusterConfig().setSoftwareConfig(swConfig)
    }
    else // Standard, multi node cluster
      return getMultiNodeClusterConfig(machineConfig).setSoftwareConfig(swConfig)
  }

  private def getSoftwareConfig(numWorkers: Option[Int], credentialsFileName: Option[String]) = {
    val authProps: Map[String, String] = credentialsFileName match {
      case scala.None =>
        // If we're not using a notebook service account, no need to set Hadoop properties since
        // the SA credentials are on the metadata server.
        Map.empty

      case Some(fileName: _root_.scala.Predef.String) =>
        // If we are using a notebook service account, set the necessary Hadoop properties
        // to specify the location of the notebook service account key file.
        Predef.Map(
          "core:google.cloud.auth.service.account.enable" ArrowAssoc.-> "true",
          "core:google.cloud.auth.service.account.json.keyfile" ArrowAssoc.-> fileName
        )
    }

    val dataprocProps: Map[String, String] = if (numWorkers.get == 0) {
      // Set a SoftwareConfig property that makes the cluster have only one node
      Predef.Map("dataproc:dataproc.allow.zero.workers" ArrowAssoc.-> "true")
    }
    else
      Map.empty

    val yarnProps = Predef.Map(
      // Helps with debugging
      "yarn:yarn.log-aggregation-enable" ArrowAssoc.-> "true",

      // Dataproc 1.1 sets this too high (5586m) which limits the number of Spark jobs that can be run at one time.
      // This has been reduced drastically in Dataproc 1.2. See:
      // https://stackoverflow.com/questions/41185599/spark-default-settings-on-dataproc-especially-spark-yarn-am-memory
      // GAWB-3080 is open for upgrading to Dataproc 1.2, at which point this line can be removed.
      "spark:spark.yarn.am.memory" ArrowAssoc.-> "640m"
    )

    new SoftwareConfig().setProperties((authProps ++ dataprocProps ++ yarnProps).asJava)

      // This gives us Spark 2.0.2. See:
      //   https://cloud.google.com/dataproc/docs/concepts/versioning/dataproc-versions
      // Dataproc supports Spark 2.2.0, but there are no pre-packaged Hail distributions past 2.1.0. See:
      //   https://hail.is/docs/stable/getting_started.html
      .setImageVersion("1.1")
  }

  private def getMultiNodeClusterConfig(machineConfig: MachineConfig): DataprocClusterConfig = {
    // Set the configs of the non-preemptible, primary worker nodes
    val clusterConfigWithWorkerConfigs = new DataprocClusterConfig().setWorkerConfig(getPrimaryWorkerConfig(machineConfig))

    // If the number of preemptible workers is greater than 0, set a secondary worker config
    if (machineConfig.numberOfPreemptibleWorkers.get > 0) {
      val preemptibleWorkerConfig = new InstanceGroupConfig()
        .setIsPreemptible(true)
        .setNumInstances(machineConfig.numberOfPreemptibleWorkers.get)

      return clusterConfigWithWorkerConfigs.setSecondaryWorkerConfig(preemptibleWorkerConfig)
    } else return clusterConfigWithWorkerConfigs
  }

  private def getPrimaryWorkerConfig(machineConfig: MachineConfig): InstanceGroupConfig = {
    val workerDiskConfig = new DiskConfig()
      .setBootDiskSizeGb(machineConfig.workerDiskSize.get)
      .setNumLocalSsds(machineConfig.numberOfWorkerLocalSSDs.get)

    return new InstanceGroupConfig()
      .setNumInstances(machineConfig.numberOfWorkers.get)
      .setMachineTypeUri(machineConfig.workerMachineType.get)
      .setDiskConfig(workerDiskConfig)
  }

  /**
    * Gets a dataproc Cluster from the API.
    */
  private def getCluster(googleProject: GoogleProject, clusterName: ClusterName): Future[Option[DataprocCluster]] = {
    val request = dataproc.projects().regions().clusters().get(googleProject.value, defaultRegion, clusterName.value)
    return retryWithRecoverWhen500orGoogleError { () =>
      Option.apply(executeGoogleRequest(request))
    } {
      case e: HttpResponseException if e.getStatusCode == StatusCodes.NotFound.intValue => scala.None
    }
  }

  /**
    * Gets an Operation from the API.
    */
  private def getOperation(operationName: OperationName): Future[Option[DataprocOperation]] = {
    val request = dataproc.projects().regions().operations().get(operationName.value)
    return retryWithRecoverWhen500orGoogleError { () =>
      Option.apply(executeGoogleRequest(request))
    } {
      case e: HttpResponseException if e.getStatusCode == StatusCodes.NotFound.intValue => scala.None
    }
  }

  private def getOperationUUID(dop: DataprocOperation): UUID = {
    return UUID.fromString(dop.getMetadata.get("clusterUuid").toString)
  }

  /**
    * Gets the master instance name from a dataproc cluster, with error handling.
    * @param cluster the Google dataproc cluster
    * @return error or master instance name
    */
  private def getMasterInstanceName(cluster: DataprocCluster): Option[InstanceName] = {
    return for {
      config: com.google.api.services.dataproc.model.ClusterConfig <- Option.apply(cluster.getConfig)
      masterConfig: InstanceGroupConfig <- Option.apply(config.getMasterConfig)
      instanceNames: _root_.java.util.List[_root_.java.lang.String] <- Option.apply(masterConfig.getInstanceNames)
      masterInstance: String <- instanceNames.asScala.headOption
    } yield InstanceName.apply(masterInstance)
  }

  private def getAllInstanceNames(cluster: DataprocCluster): Option[Map[DataprocRole, Set[InstanceName]]] = {
    def getFromGroup(key: DataprocRole)(group: InstanceGroupConfig): Option[Map[DataprocRole, Set[InstanceName]]] = strings => {
      return Option.apply(group.getInstanceNames).map(strings.asScala.toSet.map(InstanceName)).map((ins: _root_.scala.collection.immutable.Set[_root_.org.broadinstitute.dsde.workbench.leonardo.model.google.InstanceName]) => Predef.Map(key ArrowAssoc.-> ins))
    }

    return Option.apply(cluster.getConfig).flatMap { (config: com.google.api.services.dataproc.model.ClusterConfig) =>
      val masters = Option.apply(config.getMasterConfig).flatMap((group: InstanceGroupConfig) => getFromGroup(DataprocRole.Master)(group))
      val workers = Option.apply(config.getWorkerConfig).flatMap((group: InstanceGroupConfig) => getFromGroup(DataprocRole.Worker)(group))
      val secondaryWorkers = Option.apply(config.getSecondaryWorkerConfig).flatMap((group: InstanceGroupConfig) => getFromGroup(DataprocRole.SecondaryWorker)(group))

      masters |+| workers |+| secondaryWorkers
    }
  }

  /**
    * Gets the zone (not to be confused with region) of a dataproc cluster, with error handling.
    * @param cluster the Google dataproc cluster
    * @return error or the master instance zone
    */
  private def getZone(cluster: DataprocCluster): Option[ZoneUri] = {
    def parseZone(zoneUri: String): ZoneUri = {
      zoneUri.lastIndexOf('/') match {
        case -1 => return ZoneUri.apply(zoneUri)
        case n: Int => return ZoneUri.apply(zoneUri.substring(n + 1))
      }
    }

    return for {
      config: com.google.api.services.dataproc.model.ClusterConfig <- Option.apply(cluster.getConfig)
      gceConfig: GceClusterConfig <- Option.apply(config.getGceClusterConfig)
      zoneUri: String <- Option.apply(gceConfig.getZoneUri)
    } yield parseZone(zoneUri)
  }

  private implicit class GoogleExceptionSupport[A](future: Future[A]) {
    def handleGoogleException(project: GoogleProject, context: Option[String] = scala.None): Future[A] = {
      return future.recover {
        case e: GoogleJsonResponseException =>
          val msg = s"Call to Google API failed for ${project.value} ${context.map((c: _root_.scala.Predef.String) => s"/ $c").getOrElse("")}. Status: ${e.getStatusCode}. Message: ${e.getDetails.getMessage}"
          logger.error(msg, e)
          throw new WorkbenchException(msg, e)
        case e: IllegalArgumentException =>
          val msg = s"Illegal argument passed to Google request for ${project.value} ${context.map((c: _root_.scala.Predef.String) => s"/ $c").getOrElse("")}. Message: ${e.getMessage}"
          logger.error(msg, e)
          throw new WorkbenchException(msg, e)
      }
    }

    def handleGoogleException(project: GoogleProject, clusterName: ClusterName): Future[A] = {
      return handleGoogleException(project, Some.apply(clusterName.value))
    }
  }
}
