package com.github.cupenya.gateway.client

import scala.concurrent._

import akka.actor.ActorSystem
import akka.http.scaladsl._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server._
import com.github.cupenya.gateway._
import com.typesafe.scalalogging._
import kamon.Kamon

class GatewayTargetClient(val host: String, val port: Int, secured: Boolean)(implicit
    val system: ActorSystem,
    ec: ExecutionContext
) extends StrictLogging {
  private val authClient = new AuthServiceClient(
    Config.integration.authentication.host,
    Config.integration.authentication.port
  )

  private val STANDARD_PORTS = List(80, 443)
  private val API_PREFIX     = Config.gateway.prefix

  val route = Route { context =>
    val request = context.request
    val filteredHeaders =
      request.headers
        .filterNot(header =>
          header.is(Host.lowercaseName) || header.is(`Timeout-Access`.lowercaseName) || header.value.isEmpty
        )
        .appended(hostHeader)

    val eventualProxyResponse =
      if (secured) {
        logger.debug(s"Need token for request ${request.uri.path}")
        authClient.getToken(filteredHeaders).flatMap {
          case Right(tokenResponse) =>
            logger.debug(s"Token ${tokenResponse.jwt}")
            proxyRequest(
              context,
              request,
              filteredHeaders.appended(Authorization(OAuth2BearerToken(tokenResponse.jwt)))
            )
          case Left(errorResponse) =>
            logger.warn(s"Failed to retrieve token.")
            context.complete(errorResponse)
        }
      } else {
        proxyRequest(context, request, filteredHeaders)
      }

    eventualProxyResponse.transform(
      identity,
      t => {
        logger.error("Error while proxying request", t)
        t
      }
    )
  }

  private def proxyRequest(context: RequestContext, request: HttpRequest, headers: Seq[HttpHeader]) = {
    val proxiedRequest =
      context.request
        .withUri(createProxiedUri(context, request.uri))
        .withHeaders(headers)
        .withProtocol(HttpProtocols.`HTTP/1.1`)

    logger.debug(s"Proxying request: $proxiedRequest")

    Kamon
      .counter("api-gateway.proxied-requests")
      .withTag("target", host)
      .increment()

    Http(system)
      .singleRequest(proxiedRequest)
      .flatMap(context.complete(_))
  }

  private def hostHeader: Host =
    if (isStandardPort) Host(host) else Host(host, port)

  private def createProxiedUri(ctx: RequestContext, originalUri: Uri): Uri = {
    val uri = originalUri
      .withHost(host)
      .withPath(originalUri.path.dropChars(API_PREFIX.length + 1))
      .withQuery(originalUri.query())
    if (isStandardPort) uri.withPort(0) else uri.withPort(port)
  }

  private def isStandardPort =
    STANDARD_PORTS.contains(port)
}
