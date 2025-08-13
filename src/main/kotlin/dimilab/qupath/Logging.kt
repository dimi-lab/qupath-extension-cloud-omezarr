package dimilab.qupath

import ch.qos.logback.classic.Level

object Logging {
  private val logger = org.slf4j.LoggerFactory.getLogger(Logging::class.java)
}

fun <T> quietLoggers(vararg loggers: String, block: () -> T): T {
  val loggerContext = org.slf4j.LoggerFactory.getILoggerFactory() as ch.qos.logback.classic.LoggerContext

  val originalLevels = loggers.associateWith { logger -> loggerContext.getLogger(logger).level }

  try {
    loggers.forEach {
      loggerContext.getLogger(it).level = Level.ERROR
    }
    return block()
  } finally {
    originalLevels.forEach { (name, level) ->
      val logger = loggerContext.getLogger(name)
      logger.level = level
    }
  }
}