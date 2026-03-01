package com.wayrecall.tracker.maintenance.infrastructure

import zio.*
import zio.interop.catz.*
import doobie.*
import doobie.hikari.HikariTransactor
import com.wayrecall.tracker.maintenance.config.PostgresConfig

// ============================================================
// TRANSACTOR LAYER — PostgreSQL пул соединений
// ============================================================

object TransactorLayer:

  val live: ZLayer[PostgresConfig, Throwable, Transactor[Task]] =
    ZLayer.scoped {
      for
        config <- ZIO.service[PostgresConfig]
        xa <- HikariTransactor.newHikariTransactor[Task](
          driverClassName = "org.postgresql.Driver",
          url             = config.url,
          user            = config.user,
          pass            = config.password,
          connectEC       = scala.concurrent.ExecutionContext.global
        ).toScopedZIO.tap { xa =>
          ZIO.attempt {
            xa.kernel.setMaximumPoolSize(config.maxPoolSize)
            xa.kernel.setMinimumIdle(2)
            xa.kernel.setConnectionTimeout(30000)
            xa.kernel.setIdleTimeout(600000)
            xa.kernel.setMaxLifetime(1800000)
            xa.kernel.setPoolName("maintenance-hikari-pool")
          }
        }
      yield xa
    }
