# Workato custom connector for the ConfigurationSampleExtension on-prem agent extension.
#
# Companion for:
#   src/main/java/com/mycompany/onprem/configuration_sample/ConfigurationSampleExtension.java
# Agent endpoints:
#   GET /ext/configuration_sample/{connection-id}/test   — connection health check
#   GET /ext/configuration_sample/{connection-id}/info   — static+cloud params + version
#
# The extension is cloud-managed (@AgentConfigSupport(name = "configuration_sample") on
# the Java class), so the adapter segment of the URL is fixed and the second
# segment is SharedAccount#id. In production the Workato SDK framework
# populates `connection['_connection_id']` automatically for every connector
# with `secure_tunnel: true`; in the standalone SDK-tester the test
# `settings.yaml` supplies the id by hand (see PLAN.md Open item #1).
#
# `opa_extension_name` is the single source of truth for the adapter name — it
# matches `@AgentConfigSupport(name = "...")` on the Java class and the URL
# segment under `/ext/...`. The connector can be retargeted by editing one line.
opa_extension_name = 'configuration_sample'

{
  title: "On-prem #{opa_extension_name}",
  secure_tunnel: true,
  opa_extension_name: opa_extension_name,

  connection: {
    fields: [
      { name: 'timeout', label: 'Timeout', type: 'integer', optional: false,
        hint: 'Positive integer. The extension rejects the connection with HTTP 400 if ' \
          '<code>timeout</code> is missing or <= 0.' }
    ],
    authorization: { type: 'none' }
  },

  # test: lambda — Workato invokes this when the user clicks "Test connection"
  # in the UI. The Java extension validates that `timeout > 0` and returns 400
  # with a JSON body { "status": "error", "error": "<human message>", ... }.
  # The default SDK behavior on non-2xx is to raise RequestFailedError with a
  # generic "400 Bad Request" string, which hides the extension's message. The
  # after_error_response hook pulls the body, extracts the "error" field, and
  # re-raises via error(...) so the UI shows the human message.
  test: ->(connection) {
    get("http://localhost/ext/#{opa_extension_name}/#{connection['_connection_id']}/test")
      .headers('X-Workato-Connector': 'enforce')
      .after_error_response(/.*/) do |_code, body, _headers, message|
      payload = (parse_json(body) rescue nil) || {}
      error(payload['error'].presence || "#{message}: #{body}")
    end
  },

  actions: {
    get_info: {
      title: "Get #{opa_extension_name} info",
      subtitle: 'Return static + cloud parameters and connection version',
      description: "Fetch <span class='provider'>ConfigurationSampleExtension</span> state " \
        "for the current connection",

      input_fields: ->(_) { [] },

      output_fields: ->(_) {
        [
          { name: 'environment' },
          { name: 'timeout', type: 'integer' },
          { name: 'updatedAt' },
          { name: 'createdAt' }
        ]
      },

      execute: ->(connection, _input) {
        get("http://localhost/ext/#{opa_extension_name}/#{connection['_connection_id']}/info")
          .headers('X-Workato-Connector': 'enforce')
      },

      sample_output: ->(_connection, _input) {
        {
          'environment' => 'prod',
          'timeout' => 5000,
          'updatedAt' => '2026-10-30T12:00:00Z',
          'createdAt' => '2026-10-30T12:00:00Z'
        }
      }
    }
  }
}
