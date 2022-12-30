/*
 * Copyright (C) 2022 Block, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package mockwebserver3

import okhttp3.Dns
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClientTestRule
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okhttp3.tls.internal.TlsUtil.localhost
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class MockResponseSniTest {
  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  private lateinit var server: MockWebServer

  @BeforeEach
  fun setUp(server: MockWebServer) {
    this.server = server
  }

  @Test
  fun clientSendsServerNameAndServerReceivesIt() {
    val handshakeCertificates = localhost()
    server.useHttps(handshakeCertificates.sslSocketFactory())

    val client = clientTestRule.newClientBuilder()
      .sslSocketFactory(
        handshakeCertificates.sslSocketFactory(),
        handshakeCertificates.trustManager
      ).build()

    server.enqueue(MockResponse())

    val url = server.url("/")
    val call = client.newCall(Request(url = url))
    val response = call.execute()
    assertThat(response.isSuccessful).isTrue()

    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.handshakeServerNames).containsExactly(url.host)
  }

  /**
   * Use different hostnames for the TLS handshake (including SNI) and the HTTP request (in the
   * Host header).
   */
  @Test
  fun domainFronting() {
    val heldCertificate = HeldCertificate.Builder()
      .commonName("server name")
      .addSubjectAlternativeName("url-host")
      .build()
    val handshakeCertificates = HandshakeCertificates.Builder()
      .heldCertificate(heldCertificate)
      .addTrustedCertificate(heldCertificate.certificate)
      .build()
    server.useHttps(handshakeCertificates.sslSocketFactory())

    val dns = Dns {
      Dns.SYSTEM.lookup(server.hostName)
    }

    val client = clientTestRule.newClientBuilder()
      .sslSocketFactory(
        handshakeCertificates.sslSocketFactory(),
        handshakeCertificates.trustManager
      )
      .dns(dns)
      .build()

    server.enqueue(MockResponse())

    val call = client.newCall(
      Request(
        url = "https://url-host:${server.port}/".toHttpUrl(),
        headers = headersOf("Host", "header-host"),
      )
    )
    val response = call.execute()
    assertThat(response.isSuccessful).isTrue()

    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.requestUrl!!.host).isEqualTo("header-host")
    assertThat(recordedRequest.handshakeServerNames).containsExactly("url-host")
  }
}
