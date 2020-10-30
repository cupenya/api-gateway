package com.github.cupenya.service.discovery

import java.security.SecureRandom
import java.security.cert.X509Certificate

import scala.concurrent._
import scala.util.Try

import akka.actor.ActorSystem
import akka.http.scaladsl._
import akka.http.scaladsl.client.RequestBuilding._
import akka.http.scaladsl.marshallers.sprayjson._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.unmarshalling._
import akka.stream._
import akka.stream.scaladsl.Sink
import javax.net.ssl._
import spray.json._

class KubernetesServiceDiscoveryClient()(implicit system: ActorSystem, ec: ExecutionContext, materializer: Materializer)
    extends ServiceDiscoverySource[KubernetesServiceUpdate]
    with KubernetesServiceUpdateParser
    with SprayJsonSupport
    with Logging {
  override def name: String = "Kubernetes API"

  // FIXME: get rid of SSL hack
  private val trustAllCerts: Array[TrustManager] = Array(new X509TrustManager() {
    override def getAcceptedIssuers = null

    override def checkClientTrusted(x509Certificates: Array[X509Certificate], s: String): Unit = {}

    override def checkServerTrusted(x509Certificates: Array[X509Certificate], s: String): Unit = {}
  })

  private val ssl = SSLContext.getInstance("SSL")
  ssl.init(null, trustAllCerts, new SecureRandom())

  private val port = Config.`service-discovery`.kubernetes.port
  private val host = Config.`service-discovery`.kubernetes.host

  private val connectionContext: HttpsConnectionContext =
    if (port == 443) {
      ConnectionContext.httpsClient(ssl)
    } else {
      Http(system).defaultClientHttpsContext
    }

  private val request =
    Get(s"https://${host}:${port}/api/v1/services")
      .withHeaders(
        Connection("Keep-Alive"),
        Authorization(OAuth2BearerToken(Config.`service-discovery`.kubernetes.token))
      )

  def healthCheck: Future[_] =
    Http()
      .singleRequest(request, connectionContext)
      .map { response =>
        response.discardEntityBytes()
        response
      }
      .filter(_.status.isSuccess())

  def listServices: Future[List[KubernetesServiceUpdate]] = {
    Http()
      .singleRequest(request, connectionContext)
      .flatMap { response =>
        Unmarshal(response.entity)
          .to[ServiceList]
          .map(
            _.items.flatMap(so =>
              so.metadata.labels
                .flatMap(_.get("resource"))
                .map(resource => {
                  KubernetesServiceUpdate(
                    UpdateType.Addition,
                    cleanMetadataString(so.metadata.name),
                    cleanMetadataString(resource),
                    cleanMetadataString(so.metadata.namespace),
                    so.spec.ports.headOption.map(_.port).getOrElse(DEFAULT_PORT),
                    so.metadata.labels
                      .flatMap(_.get("secured"))
                      .flatMap(value => Try(value.toBoolean).toOption)
                      .getOrElse(true), // Default is secured
                    so.metadata.annotations
                      .flatMap(_.get("permissions"))
                      .flatMap(value => Try(value.parseJson.convertTo[List[Permission]]).toOption)
                      .getOrElse(Nil)
                  )
                })
            )
          )
      }
  }
}

trait KubernetesServiceUpdateParser extends DefaultJsonProtocol with Logging {

  case class PortMapping(protocol: String, port: Int, targetPort: Either[Int, String], nodePort: Option[Int])

  case class Spec(ports: List[PortMapping])

  case class Metadata(
      uid: String,
      name: String,
      namespace: String,
      labels: Option[Map[String, String]],
      annotations: Option[Map[String, String]]
  )

  case class ServiceObject(spec: Spec, metadata: Metadata)

  case class ServiceList(items: List[ServiceObject])

  implicit val portMappingFormat   = jsonFormat4(PortMapping)
  implicit val specFormat          = jsonFormat1(Spec)
  implicit val metadataFormat      = jsonFormat5(Metadata)
  implicit val serviceObjectFormat = jsonFormat2(ServiceObject)
  implicit val serviceListFormat   = jsonFormat1(ServiceList)

  // FIXME: is this really necessary?
  implicit val toServiceListUnmarshaller: Unmarshaller[HttpEntity, ServiceList] =
    Unmarshaller.withMaterializer { implicit ex => implicit mat => entity: HttpEntity =>
      entity.dataBytes
        .map(_.utf8String.parseJson)
        .collect {
          case jsObj: JsObject =>
            log.trace(s"Received data: ${jsObj}")
            jsObj.convertTo[ServiceList]
        }
        .runWith(Sink.head)
    }

  val DEFAULT_PORT = 8080

  protected def cleanMetadataString(value: String) =
    value.filterNot(_ == '"')
}

trait KubernetesNamespace {
  def namespace: String
}

trait DiscoverableThroughDns extends DiscoverableAddress with KubernetesNamespace {
  self: KubernetesNamespace =>
  val name: String

  def address: String = s"$name.$namespace"
}

case class KubernetesServiceUpdate(
    updateType: UpdateType,
    name: String,
    resource: String,
    namespace: String,
    port: Int,
    secured: Boolean,
    permissions: List[Permission]
) extends ServiceUpdate
    with DiscoverableThroughDns
