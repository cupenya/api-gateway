package com.github.cupenya.service.discovery.health

import scala.concurrent._

import akka.actor.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson._
import akka.http.scaladsl.model._
import akka.http.scaladsl.server._
import com.github.cupenya.service.discovery._
import spray.json.DefaultJsonProtocol

trait HealthCheckRoute extends Directives with DefaultJsonProtocol with SprayJsonSupport with Logging {
  self: HealthCheckService =>

  implicit def ec: ExecutionContext
  implicit def system: ActorSystem

  case class HealthCheckResults(statuses: List[HealthCheckResult])

  implicit val healthCheckResultsFormat = jsonFormat1(HealthCheckResults)

  val healthRoute =
    pathEndOrSingleSlash {
      get {
        complete(StatusCodes.OK, None)
      }
    } ~
      path("health") {
        get {
          complete {
            runChecks().map(statuses => statusCodeForStatuses(statuses) -> HealthCheckResults(statuses))
          }
        }
      }

  private def statusCodeForStatuses(statuses: List[HealthCheckResult]) =
    if (statuses.forall(_.status == HealthCheckStatus.Ok)) StatusCodes.OK else StatusCodes.InternalServerError
}
