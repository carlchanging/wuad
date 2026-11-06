package app.wuad.core

object ClashBits {
    fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    fun proxyYaml(subs: List<Triple<String, String, String>>): String {
        if (subs.isEmpty()) {
            return """
proxy-groups:
  - name: PROXY
    type: select
    proxies:
      - DIRECT
""".trimStart()
        }
        val ids = subs.joinToString("\n") { "      - ${it.first}" }
        val providers = subs.joinToString("\n") { (id, name, _) ->
            """
  $id:
    type: file
    path: ./providers/$id.yaml
    override:
      additional-prefix: "[$name] "
    health-check:
      enable: true
      url: https://www.gstatic.com/generate_204
      interval: 600
      timeout: 5000
""".trimEnd()
        }
        return """
proxy-groups:
  - name: AUTO
    type: url-test
    use:
$ids
    url: https://www.gstatic.com/generate_204
    interval: 300
    tolerance: 50
    lazy: true
  - name: PROXY
    type: select
    use:
$ids
    proxies:
      - AUTO
      - DIRECT
proxy-providers:
$providers
""".trimStart()
    }
}
