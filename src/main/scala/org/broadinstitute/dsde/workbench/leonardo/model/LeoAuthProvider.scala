package org.broadinstitute.dsde.workbench.leonardo.model

import java.util.UUID

import com.typesafe.config.Config
import org.broadinstitute.dsde.workbench.model.UserInfo

import scala.concurrent.Future

object ProjectActions {
  sealed trait ProjectAction extends Product with Serializable
  case object ListClusters extends ProjectAction
  case object CreateClusters extends ProjectAction
  val allActions = Seq(ListClusters, CreateClusters)
}

object NotebookClusterActions {
  sealed trait NotebookClusterAction extends Product with Serializable
  case object GetClusterStatus extends NotebookClusterAction
  case object ConnectToCluster extends NotebookClusterAction
  case object SyncDataToCluster extends NotebookClusterAction
  case object DeleteCluster extends NotebookClusterAction
  val allActions = Seq(GetClusterStatus, ConnectToCluster, SyncDataToCluster, DeleteCluster)

}

abstract class LeoAuthProvider(authConfig: Config) {
  /**
    * @param userInfo The user in question
    * @param action The project-level action (above) the user is requesting
    * @param googleProject The Google project to check in
    * @return If the given user has permissions in this project to perform the specified action.
    */
  def hasProjectPermission(userInfo: UserInfo, action: ProjectActions.ProjectAction, googleProject: String): Future[Boolean]

  /**
    * Leo calls this method to verify if the user has permission to perform the given action on a specific notebook cluster.
    * It may call this method passing in a cluster that doesn't exist. Return Future.successful(false) if so.
    *
    * @param userInfo      The user in question
    * @param action        The cluster-level action (above) the user is requesting
    * @param googleProject The Google project the cluster was created in
    * @param clusterName   The user-provided name of the Dataproc cluster
    * @return If the userEmail has permission on this individual notebook cluster to perform this action
    */
  def hasNotebookClusterPermission(userInfo: UserInfo, action: NotebookClusterActions.NotebookClusterAction, googleProject: String, clusterName: String): Future[Boolean]

  //Notifications that Leo has created/destroyed clusters. Allows the auth provider to register things.

  /**
    * Leo calls this method to notify the auth provider that a new notebook cluster has been created.
    * The returned future should complete once the provider has finished doing any associated work.
    * Returning a failed Future will prevent the cluster from being created, and will call notifyClusterDeleted for the same cluster.
    * Leo will wait, so be timely!
    *
    * @param userEmail     The email address of the user in question
    * @param googleProject The Google project the cluster was created in
    * @param clusterName   The user-provided name of the Dataproc cluster
    * @return A Future that will complete when the auth provider has finished doing its business.
    */
  def notifyClusterCreated(userEmail: String, googleProject: String, clusterName: String): Future[Unit]

  /**
    * Leo calls this method to notify the auth provider that a notebook cluster has been deleted.
    * The returned future should complete once the provider has finished doing any associated work.
    * Leo will wait, so be timely!
    *
    * @param userEmail     The email address of the user in question
    * @param googleProject The Google project the cluster was created in
    * @param clusterName   The user-provided name of the Dataproc cluster
    * @return A Future that will complete when the auth provider has finished doing its business.
    */
  def notifyClusterDeleted(userEmail: String, googleProject: String, clusterName: String): Future[Unit]
}