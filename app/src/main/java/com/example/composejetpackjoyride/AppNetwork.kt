package com.example.composejetpackjoyride

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.util.concurrent.TimeUnit

internal object AppNetwork {
    private val bootstrapClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .build()

    private val dns = DnsOverHttps.Builder()
        .client(bootstrapClient)
        .url("https://common.dot.dns.yandex.net/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("77.88.8.8"),
            InetAddress.getByName("77.88.8.1")
        )
        .build()

    val client: OkHttpClient = bootstrapClient.newBuilder()
        .dns(dns)
        .build()
}
