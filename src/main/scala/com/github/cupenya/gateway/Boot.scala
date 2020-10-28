package com.github.cupenya.gateway

import akka.actor.{ ActorSystem, Props }
import akka.http.scaladsl._
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.MethodRejection
import com.github.cupenya.gateway.client.AuthServiceClient
import com.github.cupenya.gateway.configuration._
import com.github.cupenya.gateway.health._
import com.github.cupenya.gateway.model._
import com.github.cupenya.gateway.server._
import com.github.cupenya.service.discovery.health._
import com.github.cupenya.service.discovery.{ Config => _, _ }
import kamon.Kamon

object Boot
    extends App
    with GatewayHttpService
    with ApiDashboardService
    with HealthCheckRoute
    with HealthCheckService
    with CorsRoute {
  Kamon.init()

  implicit val system: ActorSystem = ActorSystem()
  implicit val ec                  = system.dispatcher

  private val gatewayInterface = Config.gateway.interface
  private val gatewayPort      = Config.gateway.port

  private val dashboardInterface = Config.dashboard.interface
  private val dashboardPort      = Config.dashboard.port

  val authClient = new AuthServiceClient(
    Config.integration.authentication.host,
    Config.integration.authentication.port
  )

  log.info(s"Starting API gateway using gateway interface $gatewayInterface and port $gatewayPort")

  val rootRoute =
    defaultCORSHeaders {
      cancelRejection(MethodRejection(HttpMethods.OPTIONS)) {
        options {
          complete(StatusCodes.OK -> None)
        }
      } ~ authRoute ~ healthRoute ~ gatewayRoute
    }

  val mainDashboardRoute =
    defaultCORSHeaders {
      cancelRejection(MethodRejection(HttpMethods.OPTIONS)) {
        options {
          complete(StatusCodes.OK -> None)
        }
      } ~ dashboardRoute
    }

  Http()
    .newServerAt(gatewayInterface, gatewayPort)
    .bind(rootRoute)
    .transform(
      binding => log.info(s"REST gateway interface bound to ${binding.localAddress} "),
      { t => log.error(s"Couldn't start API gateway", t); sys.exit(1) }
    )

  log.info(s"Starting API gateway dashboard using interface $dashboardInterface and port $dashboardPort")

  Http()
    .newServerAt(dashboardInterface, dashboardPort)
    .bind(mainDashboardRoute)
    .transform(
      binding => log.info(s"REST gateway dashboard interface bound to ${binding.localAddress} "),
      { t => log.error(s"Couldn't start API gateway dashboard", t); sys.exit(1) }
    )

  private def handleServiceUpdates[T <: ServiceUpdate](allServiceUpdates: List[T]) = {
    val serviceUpdates = allServiceUpdates.filter { upd =>
      Config.integration.kubernetes.namespaces.isEmpty || Config.integration.kubernetes.namespaces
        .contains(upd.namespace)
    }
    val currentResources = GatewayConfigurationManager.currentConfig().targets.keys.toList
    val toDelete         = currentResources.filterNot(serviceUpdates.map(_.resource).contains)
    log.debug(s"Deleting $toDelete")
    toDelete.foreach(GatewayConfigurationManager.deleteGatewayTarget)

    // TODO: handle config updates
    val newResources = serviceUpdates.filterNot(su => currentResources.contains(su.resource))
    log.debug(s"New services $newResources")
    newResources.foreach(serviceUpdate => {
      val gatewayTarget =
        GatewayTarget(serviceUpdate.resource, serviceUpdate.address, serviceUpdate.port, serviceUpdate.secured)
      log.info(s"Registering new gateway target $gatewayTarget")
      GatewayConfigurationManager.upsertGatewayTarget(gatewayTarget)
    })
  }

  val serviceDiscoveryAgent =
    //        system.actorOf(Props(new ServiceDiscoveryAgent[StaticServiceUpdate](new StaticServiceListSource)))
    system.actorOf(
      Props(
        new ServiceDiscoveryAgent[KubernetesServiceUpdate](new KubernetesServiceDiscoveryClient, handleServiceUpdates)
      )
    )

  serviceDiscoveryAgent ! ServiceDiscoveryAgent.WatchServices

  override def checks: List[HealthCheck] =
    List(new ServiceDiscoveryHealthCheck(serviceDiscoveryAgent), new AuthServiceHealthCheck(authClient))
}
