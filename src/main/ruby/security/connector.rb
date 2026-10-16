# Workato custom connector for the SecurityExtension on-prem agent extension.
#
# Companion for: src/main/java/com/mycompany/onprem/SecurityExtension.java
# Agent endpoint: POST /ext/{profile}/computeDigest
#   request  : { "payload": "<string>" }
#   response : { "signature": "<64-char lowercase hex of SHA-256(secret || payload)>" }
#
# The `secret` is configured on the agent side, under the extension's YAML
# section in config.yml — the recipe never sees it.

{
  title: 'On-prem security (SHA-256)',
  secure_tunnel: true,

  connection: {
    fields: [
      {
        name: 'profile',
        label: 'Extension profile',
        hint: 'Name of the on-prem SecurityExtension profile declared under ' \
              '<code>extensions:</code> in the agent <code>config.yml</code>.',
        optional: false
      }
    ],
    authorization: { type: 'none' }
  },

  test: ->(connection) {
    post("http://localhost/ext/#{connection['profile']}/computeDigest",
         payload: 'ping')
      .headers('X-Workato-Connector': 'enforce')
  },

  actions: {
    sha256_digest: {
      title: 'Compute SHA-256 digest',
      subtitle: 'Sign payload with the on-prem secret',
      description: "Create <span class='provider'>SHA-256</span> digest of " \
                   "<span class='provider'>secret || payload</span>",

      input_fields: ->(_) {
        [
          { name: 'payload', label: 'Payload', optional: false,
            hint: 'UTF-8 string to sign.' }
        ]
      },

      output_fields: ->(_) {
        [
          { name: 'signature', label: 'Signature',
            hint: 'Lowercase hex, 64 characters.' }
        ]
      },

      execute: ->(connection, input) {
        post("http://localhost/ext/#{connection['profile']}/computeDigest",
             payload: input['payload'])
          .headers('X-Workato-Connector': 'enforce')
      },

      sample_output: ->(_connection, _input) {
        { 'signature' => '0' * 64 }
      }
    }
  }
}
