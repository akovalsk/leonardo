package org.broadinstitute.dsde.workbench.leonardo.dao

import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.util.health.StatusJsonSupport._
import org.broadinstitute.dsde.workbench.util.health.Subsystems.{GoogleIam, OpenDJ}
import org.broadinstitute.dsde.workbench.util.health.{StatusCheckResponse, SubsystemStatus}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Seconds, Span}
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}

import scala.concurrent.Future

/**
  * Created by rtitle on 10/16/17.
  */
class HttpSamDAOSpec extends FlatSpec with Matchers with BeforeAndAfterAll with ScalatestRouteTest with ScalaFutures {
  implicit val patience = PatienceConfig(timeout = scaled(Span(10, Seconds)))
  implicit val errorReportSource = ErrorReportSource("test")
  var bindingFutureHealthy: Future[ServerBinding] = _
  var bindingFutureUnhealthy: Future[ServerBinding] = _

  val healthyPort = 9090
  val unhealthyPort = 9091
  val unknownPort = 9092

  val dao = new HttpSamDAO(s"http://localhost:$healthyPort")
  val unhealthyDAO = new HttpSamDAO(s"http://localhost:$unhealthyPort")
  val unknownDAO = new HttpSamDAO(s"http://localhost:$unknownPort")

  override def beforeAll(): Unit = {
    super.beforeAll()
    bindingFutureHealthy = Http().bindAndHandle(backendRoute, "0.0.0.0", healthyPort)
    bindingFutureUnhealthy = Http().bindAndHandle(unhealthyRoute, "0.0.0.0", unhealthyPort)
  }

  override def afterAll(): Unit = {
    bindingFutureHealthy.flatMap(_.unbind())
    bindingFutureUnhealthy.flatMap(_.unbind())
    super.afterAll()
  }

  val returnedOkStatus = StatusCheckResponse(true, Map(OpenDJ -> SubsystemStatus(true, None), GoogleIam -> SubsystemStatus(true, None)))
  val expectedOkStatus = StatusCheckResponse(true, Map.empty)
  val notOkStatus = StatusCheckResponse(false, Map(OpenDJ -> SubsystemStatus(false, Option(List("OpenDJ is down. Panic!"))), GoogleIam -> SubsystemStatus(true, None)))

  val unhealthyRoute: Route =
    path("status") {
      get {
        complete(StatusCodes.InternalServerError -> notOkStatus)
      }
    }

  val backendRoute: Route =
    path("status") {
      get {
        complete(returnedOkStatus)
      }
    }

  "HttpSamDAO" should "get Sam status" in {
    dao.getStatus().futureValue shouldBe expectedOkStatus
    unhealthyDAO.getStatus().futureValue shouldBe notOkStatus
    val exception = unknownDAO.getStatus().failed.futureValue
    exception shouldBe a [CallToSamFailedException]
    exception.asInstanceOf[CallToSamFailedException].status shouldBe StatusCodes.InternalServerError
  }
}
