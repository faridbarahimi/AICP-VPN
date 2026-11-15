package com.v2ray.ang.fmt

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AicpExternalConfigImporterTest {
    @Test
    fun singBoxJsonConvertsSupportedVlessAndSkipsNonProxyOutbounds() {
        val input = """{
          "outbounds": [
            {"type":"direct", "tag":"direct"},
            {"type":"vless", "tag":"de-node", "server":"edge.example.org", "server_port":443,
             "uuid":"00000000-0000-0000-0000-000000000001",
             "tls":{"enabled":true,"server_name":"cdn.example.org","utls":{"enabled":true,"fingerprint":"chrome"}},
             "transport":{"type":"ws","path":"/tunnel","headers":{"Host":"cdn.example.org"}}}
          ]
        }"""
        val converted = AicpExternalConfigImporter.expand(input).orEmpty()
        assertTrue(converted.contains("vless://"))
        assertTrue(converted.contains("edge.example.org:443"))
        assertTrue(converted.contains("fp=chrome"))
        assertTrue(converted.contains("type=ws"))
        assertFalse(converted.contains("direct://"))
    }

    @Test
    fun clashYamlConvertsBasicTrojanAndSkipsUnsupportedProxyTypes() {
        val input = """mixed-port: 7890
proxies:
  - name: "fast-de"
    type: trojan
    server: edge.example.org
    port: 443
    password: "sample-secret"
    sni: cdn.example.org
    tls: true
  - name: "unsupported"
    type: openvpn
    server: vpn.example.org
    port: 1194
"""
        val converted = AicpExternalConfigImporter.expand(input).orEmpty()
        assertTrue(converted.contains("trojan://"))
        assertTrue(converted.contains("edge.example.org:443"))
        assertFalse(converted.contains("openvpn://"))
    }

    @Test
    fun malformedOrUnrecognizedDocumentIsNotTreatedAsImported() {
        assertTrue(AicpExternalConfigImporter.expand("{not-json}").isNullOrBlank())
        assertTrue(AicpExternalConfigImporter.expand("hello world").isNullOrBlank())
    }

    @Test
    fun singBoxRejectsUnknownTransportInsteadOfDowngradingToTcp() {
        val input = """{
          "outbounds": [{"type":"vless", "server":"edge.example.org", "server_port":443,
            "uuid":"00000000-0000-0000-0000-000000000001",
            "transport":{"type":"quic"}}]
        }"""
        assertTrue(AicpExternalConfigImporter.expand(input).isNullOrBlank())
    }

    @Test
    fun singBoxRejectsTransportUnsupportedByOutboundProtocol() {
        val input = """{
          "outbounds": [{"type":"shadowsocks", "server":"edge.example.org", "server_port":443,
            "method":"aes-128-gcm", "password":"sample-secret",
            "transport":{"type":"ws","path":"/tunnel"}}]
        }"""
        assertTrue(AicpExternalConfigImporter.expand(input).isNullOrBlank())
    }

    @Test
    fun singBoxPreservesGrpcAndHttpUpgradeTransports() {
        val input = """{
          "outbounds": [
            {"type":"vless", "tag":"grpc", "server":"grpc.example.org", "server_port":443,
             "uuid":"00000000-0000-0000-0000-000000000001", "transport":{"type":"grpc","service_name":"edge"}},
            {"type":"vless", "tag":"upgrade", "server":"upgrade.example.org", "server_port":443,
             "uuid":"00000000-0000-0000-0000-000000000002", "transport":{"type":"httpupgrade","path":"/edge"}}
          ]
        }"""
        val converted = AicpExternalConfigImporter.expand(input).orEmpty()
        assertTrue(converted.contains("type=grpc"))
        assertTrue(converted.contains("type=httpupgrade"))
    }

}
