package com.github.cupenya.gateway.server

import akka.actor.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.server._
import akka.stream.Materializer
import com.github.cupenya.gateway.client.{ AuthServiceClient, GatewayTargetClient, LoginData }
import com.github.cupenya.gateway.configuration.{ GatewayConfiguration, GatewayConfigurationManager }
import com.github.cupenya.gateway.Config
import com.typesafe.scalalogging.StrictLogging
import spray.json.DefaultJsonProtocol

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext

trait GatewayTargetDirectives extends Directives {
  def serviceRouteForResource(config: GatewayConfiguration, prefix: String): Directive[Tuple1[GatewayTargetClient]] =
    pathPrefix(GatewayTargetPathMatcher(config, prefix)).flatMap(provide)
}

case class GatewayTargetPathMatcher(config: GatewayConfiguration, prefix: String) extends PathMatcher1[GatewayTargetClient] with StrictLogging {
  import Path._
  import PathMatcher._

  def apply(path: Path): Matching[Tuple1[GatewayTargetClient]] =
    matchPathToGatewayTarget(path)

  @tailrec
  private def matchPathToGatewayTarget(path: Path): Matching[Tuple1[GatewayTargetClient]] = path match {
    case Empty =>
      logger.debug(s"No match found for path: $path")
      Unmatched
    case Segment(apiPrefix, tail) if apiPrefix == prefix =>
      logger.debug(s"Match found for path: $tail")
      matchRemainingPathToGatewayTarget(tail)
    case Segment(head, tail) =>
      matchPathToGatewayTarget(tail)
    case Slash(tail) => matchPathToGatewayTarget(tail)
  }

  @tailrec
  private def matchRemainingPathToGatewayTarget(path: Path): Matching[Tuple1[GatewayTargetClient]] = path match {
    case Slash(tail) =>
      logger.debug(s"Matching  additional path with  slash  ${tail}")
      matchRemainingPathToGatewayTarget(tail)
    case s @ Segment(head, _) =>
      logger.debug(s"Matching additional path with  slash  ${head}, found ${config.targets.get(head).map(x => (x.host, x.port))}")
      config.targets.get(head)
        .map(gatewayTarget => Matched(s, Tuple1(gatewayTarget)))
        .getOrElse(Unmatched)
    case _ => Unmatched
  }
}

trait GatewayHttpService extends GatewayTargetDirectives
    with SprayJsonSupport
    with DefaultJsonProtocol
    with Directives {

  implicit def system: ActorSystem

  implicit def ec: ExecutionContext

  implicit val materializer: Materializer

  implicit val loginDataFormat = jsonFormat2(LoginData)

  val gatewayRoute: Route = (ctx: RequestContext) =>
    serviceRouteForResource(GatewayConfigurationManager.currentConfig(), Config.gateway.prefix)(_.route)(ctx)

  val authClient: AuthServiceClient

  val authRoute =
    pathPrefix(Config.gateway.prefix / "auth") {
      path("currentUser") {
        get {
          extractRequest { req =>
            complete(authClient.currentUser(req.headers))
          }
        }
      } ~
        path("login") {
          post {
            entity(as[LoginData]) { loginData =>
              complete(authClient.login(loginData))
            }
          }
        } ~
        path("logout") {
          post {
            complete(authClient.logout)
          }
        }
    }
}

