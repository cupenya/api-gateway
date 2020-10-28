package com.github.cupenya.gateway

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._

package object client {
  implicit class RichHeaders(headers: List[HttpHeader]) {

    def noEmptyHeaders: List[HttpHeader] =
      headers.filterNot(_.value.isEmpty)

    def -[T](header: ModeledCompanion[T]): List[HttpHeader] =
      headers.filterNot(_.is(header.lowercaseName))
  }
}
