package com.typesafe.tools.mima.core.util.log

import org.apache.maven.plugin.logging.Log

class MavenLogging(log: Log) extends Logging {
  override def debug(str: String): Unit = log.debug(str)
  override def verbose(str: String): Unit = log.info(str)
  override def warn(str: String): Unit = log.warn(str)
  override def error(str: String): Unit = log.error(str)
}
