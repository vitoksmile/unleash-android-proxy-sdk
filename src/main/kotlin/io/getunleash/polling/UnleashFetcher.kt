package io.getunleash.polling

import io.getunleash.UnleashConfig
import io.getunleash.UnleashContext
import io.getunleash.data.FetchResponse
import io.getunleash.data.ProxyResponse
import io.getunleash.data.Status
import io.getunleash.data.Toggle
import io.getunleash.data.ToggleResponse
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.closeQuietly
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.IOException
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * Http Client for fetching data from Unleash Proxy.
 * By default creates an OkHttpClient with readTimeout set to 2 seconds and a cache of 10 MBs
 * @param unleashConfig - Configuration for unleash - see docs for [io.getunleash.UnleashConfig]
 * @param httpClient - the http client to use for fetching toggles from Unleash proxy
 */
open class UnleashFetcher(
    val unleashConfig: UnleashConfig, val httpClient: OkHttpClient = OkHttpClient.Builder().readTimeout(
        Duration.ofSeconds(2)
    ).cache(
        Cache(
            directory = Files.createTempDirectory("unleash_toggles").toFile(),
            maxSize = 10L * 1024L * 1024L // Use 10 MB as max
        )
    ).build()
) : Closeable {
    companion object {
        val logger: Logger = LoggerFactory.getLogger(UnleashFetcher::class.java)
        val TOGGLE_BACKUP_NAME = "unleash_proxy_toggles"
    }
    private val json: Json = Json
    private val proxyUrl = unleashConfig.proxyUrl.toHttpUrl()

    open fun getTogglesAsync(ctx: UnleashContext): CompletableFuture<ToggleResponse> {
        return getResponseAsync(ctx).thenApply { response ->
            if(response.isFetched()) {
                ToggleResponse(response.status, response.config!!.toggles.groupBy { it.name }.mapValues { (_, v) -> v.first() })
            } else {
                ToggleResponse(response.status)
            }
        }
    }

    fun getResponseAsync(ctx: UnleashContext): CompletableFuture<FetchResponse> {
        val contextUrl = buildContextUrl(ctx)
        val request = Request.Builder().url(contextUrl).header("Authorization", unleashConfig.clientSecret).build()
        val fetch = CompletableFuture<FetchResponse>()
        this.httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                logger.warn("An error occurred when fetching the latest configuration.", e)
                fetch.complete(FetchResponse(Status.FAILED))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    when {
                        res.isSuccessful -> {
                            if (res.cacheResponse != null) {
                                fetch.complete(FetchResponse(Status.NOTMODIFIED))
                            } else {
                                res.body?.let { b ->
                                    try {
                                        val proxyResponse =
                                            json.decodeFromString(ProxyResponse.serializer(), b.string())
                                        fetch.complete(FetchResponse(Status.FETCHED, proxyResponse))
                                    } catch (e: Exception) {
                                        // If we fail to parse, just keep data
                                        fetch.complete(FetchResponse(Status.FAILED))
                                    }
                                }
                            }
                        }
                        res.code == 304 -> {
                            logger.debug("Fetch was successful; config not modified")
                            fetch.complete(FetchResponse(Status.NOTMODIFIED))
                        }
                        res.code == 401 -> {
                            logger.error("Double check your SDK key")
                            fetch.complete(FetchResponse(Status.FAILED))
                        }
                        else -> {
                            logger.warn("Unexpected result from Unleash Proxy, keep old result")
                            fetch.complete(FetchResponse(Status.FAILED))
                        }
                    }
                }
            }
        })
        return fetch
    }

    private fun buildContextUrl(ctx: UnleashContext): HttpUrl {
        var contextUrl = proxyUrl.newBuilder().addQueryParameter("appName", ctx.appName)
            .addQueryParameter("env", ctx.environment)
            .addQueryParameter("userId", ctx.userId)
            .addQueryParameter("remoteAddress", ctx.remoteAddress)
            .addQueryParameter("sessionId", ctx.sessionId)
        ctx.properties.entries.forEach {
            contextUrl = contextUrl.addQueryParameter(it.key, it.value)
        }
        return contextUrl.build()
    }

    override fun close() {
        this.httpClient.dispatcher.executorService.shutdownNow()
        this.httpClient.connectionPool.evictAll()
        this.httpClient.cache?.closeQuietly()
    }
}