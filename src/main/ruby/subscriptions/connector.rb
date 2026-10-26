# Workato custom connector for the SubscriptionsExtension on-prem agent extension.
#
# Companion for:
#   src/main/java/com/mycompany/onprem/subscriptions/SubscriptionsExtension.java
# Agent endpoint:
#   GET /ext/subscriptions/{connection-id}/inspect
#
# The extension is cloud-managed (@AgentConfigSupport(name = "subscriptions") on
# the Java class), so the adapter segment of the URL is fixed ("subscriptions")
# and the second segment is SharedAccount#id. In production the Workato SDK
# framework populates `connection['_connection_id']` automatically for every
# connector with `secure_tunnel: true`; in the standalone SDK-tester the test
# `settings.yaml` supplies the id by hand (see src/test/ruby/subscriptions/
# settings.yaml and the PLAN.md Open item #1).
#
# This connector is a demo of the Subscription API — it just forwards the
# extension's own JSON back to the recipe so the author can see what the
# extension observed through `AgentConnectionLifecycleV1` callbacks and through
# merged static + connection config.
{
  title: 'On-prem subscriptions inspector',
  secure_tunnel: true,

  connection: {
    fields: [
      { name: 'label', label: 'Label', optional: true,
        hint: 'Free-form string the extension exposes under <code>connection.label</code>. ' \
          'Resolved per-request via <code>@Value Supplier&lt;String&gt;</code> on the ' \
          'extension controller.' },
      { name: 'host', label: 'Host', optional: true,
        hint: 'Example cloud-pushed field, surfaced in the inspect response under ' \
          '<code>connection.allKeys</code>.' },
      { name: 'port', label: 'Port', optional: true, type: 'integer' }
    ],
    authorization: { type: 'none' }
  },

  # In production `connection['_connection_id']` is set by the Workato SDK
  # framework from SharedAccount#id. In the standalone tester the value comes
  # from settings.yaml (see Open item #1 in PLAN.md).
  test: ->(connection) {
    get("http://localhost/ext/subscriptions/#{connection['_connection_id']}/inspect")
      .headers('X-Workato-Connector': 'enforce')
  },

  actions: {
    inspect: {
      title: 'Inspect subscription state',
      subtitle: 'Return static params, connection params, and lifecycle counters',
      description: "Fetch <span class='provider'>SubscriptionsExtension</span> state " \
        "for the current connection",

      input_fields: ->(_) { [] },

      # Rough shape of the extension's JSON envelope. Workato's data-pill UI uses
      # this schema when the recipe author wires the response into downstream
      # steps; the extension itself serialises more fields than are listed here.
      output_fields: ->(_) {
        [
          { name: 'adapter' },
          { name: 'connectionId', type: 'integer' },
          { name: 'connectionIdFromPath', type: 'integer' },
          { name: 'connectionVersion', type: 'integer' },
          { name: 'staticPerAdapter', type: 'object', properties: [
            { name: 'environment' },
            { name: 'region' },
            { name: 'bannerText' }
          ] },
          { name: 'connection', type: 'object', properties: [
            { name: 'label-via-supplier' },
            { name: 'label-via-config' },
            { name: 'allKeys', type: 'array', of: 'string' }
          ] },
          { name: 'lifecycle', type: 'object', properties: [
            { name: 'addedTotal', type: 'integer' },
            { name: 'updatedTotal', type: 'integer' },
            { name: 'removedTotal', type: 'integer' },
            { name: 'trackedConnections', type: 'array', of: 'integer' },
            { name: 'thisConnection', type: 'object', properties: [
              { name: 'firstSeenAt' },
              { name: 'lastSeenAt' },
              { name: 'updateCount', type: 'integer' },
              { name: 'lastKnownKeys', type: 'array', of: 'string' }
            ] }
          ] }
        ]
      },

      execute: ->(connection, _input) {
        get("http://localhost/ext/subscriptions/#{connection['_connection_id']}/inspect")
          .headers('X-Workato-Connector': 'enforce')
      },

      sample_output: ->(_connection, _input) {
        {
          'adapter' => 'subscriptions',
          'connectionId' => 42,
          'connectionIdFromPath' => 42,
          'connectionVersion' => 1,
          'staticPerAdapter' => {
            'environment' => 'prod',
            'region' => 'us-east-1',
            'bannerText' => 'subscriptions-demo'
          },
          'connection' => {
            'label-via-supplier' => 'primary',
            'label-via-config' => 'primary',
            'allKeys' => %w[host label port]
          },
          'lifecycle' => {
            'addedTotal' => 1,
            'updatedTotal' => 0,
            'removedTotal' => 0,
            'trackedConnections' => [42],
            'thisConnection' => {
              'firstSeenAt' => '2026-10-26T12:00:00Z',
              'lastSeenAt' => '2026-10-26T12:00:00Z',
              'updateCount' => 0,
              'lastKnownKeys' => %w[host label port]
            }
          }
        }
      }
    }
  }
}
