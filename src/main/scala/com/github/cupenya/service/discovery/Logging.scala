package com.github.cupenya.service.discovery

import com.typesafe.scalalogging._

trait Logging extends LazyLogging {
  protected[this] lazy val log = logger
}
