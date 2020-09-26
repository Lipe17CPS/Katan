package me.devnatan.katan.core

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientImpl
import com.github.dockerjava.core.KeystoreSSLConfig
import com.github.dockerjava.core.LocalDirectorySSLConfig
import com.github.dockerjava.okhttp.OkDockerHttpClient
import com.typesafe.config.Config
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import me.devnatan.katan.api.Katan
import me.devnatan.katan.api.manager.AccountManager
import me.devnatan.katan.api.manager.ServerManager
import me.devnatan.katan.common.util.get
import me.devnatan.katan.core.database.DatabaseConnector
import me.devnatan.katan.core.database.SUPPORTED_CONNECTORS
import me.devnatan.katan.core.database.jdbc.JDBCConnector
import me.devnatan.katan.core.exceptions.throwSilent
import me.devnatan.katan.core.manager.DefaultAccountManager
import me.devnatan.katan.core.manager.DockerServerManager
import me.devnatan.katan.core.repository.JDBCServersRepository
import org.slf4j.LoggerFactory
import java.io.UncheckedIOException
import java.net.ConnectException
import java.security.KeyStore

class KatanCore(val config: Config) :
    CoroutineScope by CoroutineScope(CoroutineName("Katan")), Katan {

    companion object {

        const val DATABASE_DIALECT_FALLBACK = "H2"
        val logger = LoggerFactory.getLogger(Katan::class.java)!!

    }

    lateinit var database: DatabaseConnector
    lateinit var docker: DockerClient

    override lateinit var accountManager: AccountManager
    override lateinit var serverManager: ServerManager

    private suspend fun database() {
        val db = config.getConfig("database")
        val dialect = db.get("source", DATABASE_DIALECT_FALLBACK)
        logger.info("Using $dialect as database dialect (fallback to ${DATABASE_DIALECT_FALLBACK}).")

        val dialectSettings = runCatching {
            db.getConfig(dialect.toLowerCase())
        }.onFailure {
            throwSilent(IllegalArgumentException("Dialect properties not found: $dialect."), logger)
        }.getOrThrow()

        connectWith(dialect, dialectSettings, db.get("strict", true))
    }

    private suspend fun connectWith(dialect: String, config: Config, strict: Boolean) {
        val dialectName = dialect.toLowerCase()
        if (!SUPPORTED_CONNECTORS.containsKey(dialectName))
            throwSilent(IllegalArgumentException("Database dialect $dialect is not supported"), logger)

        val (connector, settings) = SUPPORTED_CONNECTORS.getValue(dialectName).invoke(config)
        logger.info("Initializing connector ${connector::class.simpleName}.")

        runCatching {
            database = connector
            connector.connect(settings)
        }.onFailure {
            logger.error("Unable to connect to $dialect database.")
            if (strict || dialect.equals(DATABASE_DIALECT_FALLBACK, true)) {
                logger.error("{}", it.toString())
                throw it
            }

            logger.info("Strict mode is disabled, connecting again using fallback dialect $DATABASE_DIALECT_FALLBACK.")
            connectWith(DATABASE_DIALECT_FALLBACK, config, strict)
        }
    }

    private fun docker() {
        logger.info("Configuring Docker...")
        val dockerLogger = LoggerFactory.getLogger(DockerClient::class.java)
        val dockerConfig = config.getConfig("docker")
        val tls = dockerConfig.get("tls.verify", false)
        val clientConfig = DefaultDockerClientConfig.createDefaultConfigBuilder()
            .withDockerHost(dockerConfig.getString("host"))
            .withDockerTlsVerify(tls)

        if (tls) {
            dockerLogger.info("TLS verification is enabled, will switching between HTTP protocols.")
            clientConfig.withDockerCertPath(dockerConfig.getString("tls.certPath"))
        }

        if (dockerConfig.get("ssl.enabled", true)) {
            clientConfig.withCustomSslConfig(
                when (dockerConfig.getString("ssl.provider")) {
                    "CERT" -> {
                        val path = dockerConfig.getString("ssl.certPath")
                        dockerLogger.info("Docker SSL certification path located at {}.", path)
                        LocalDirectorySSLConfig(path)
                    }
                    "KEY_STORE" -> {
                        val type = dockerConfig.get("keyStore.provider") ?: KeyStore.getDefaultType()
                        val keystore = KeyStore.getInstance(type)
                        dockerLogger.info(
                            "Using {} as SSL key store type.",
                            type
                        )

                        KeystoreSSLConfig(keystore, dockerConfig.getString("keyStore.password"))
                    }
                    else -> throwSilent(IllegalArgumentException("Unrecognized Docker SSL provider. Must be: CERT or KEY_STORE"), logger)
                }
            )
        }

        val properties = dockerConfig.getConfig("properties")
        val httpConfig = clientConfig.build()
        docker = runCatching {
            DockerClientImpl.getInstance(
                httpConfig, OkDockerHttpClient.Builder()
                    .dockerHost(httpConfig.dockerHost)
                    .sslConfig(httpConfig.sslConfig)
                    .connectTimeout(properties.getInt("connectTimeout"))
                    .readTimeout(properties.getInt("readTimeout"))
                    .build()
            )
        }.onFailure {
            throwSilent(it, dockerLogger)
        }.getOrThrow()

        // sends a ping to see if the connection will be established.
        try {
            dockerLogger.info("Trying to connect to ${httpConfig.dockerHost}...")
            docker.pingCmd().exec()
        } catch (e: ConnectException) {
            throwSilent(e, dockerLogger)
        } catch (e: UncheckedIOException) {
            throwSilent(e.cause!!, dockerLogger)
        }
    }

    suspend fun start() {
        logger.info("Platform: ${System.getProperty("os.name")} (${System.getProperty("os.arch")})")
        database()
        docker()
        accountManager = DefaultAccountManager(this)
        serverManager = DockerServerManager(
            this, when (database) {
                is JDBCConnector -> JDBCServersRepository(this, database as JDBCConnector)
                else -> throwSilent(IllegalArgumentException("No servers repository available for $database"), logger)
            }
        )
    }

}