# Workato custom connector for the NtlmExtension on-prem agent extension.
#
# Companion for: src/main/java/com/mycompany/onprem/NtlmExtension.java
# Agent endpoint: POST /ext/{profile}/request
#   required headers:
#     X-Workato-NTLM-auth : "username:password[@domain[/workstation]]"
#     X-Workato-NTLM-url  : target URL to GET through NTLM
#   response body        : upstream response body on HTTP 200,
#                          empty body otherwise (status is forwarded).
#
# The extension performs an NTLM-authenticated GET to the target URL and
# proxies the body back verbatim. Credentials live in the Workato connection
# so recipes never embed them in input.

{
  title: 'On-prem NTLM proxy',
  secure_tunnel: true,

  connection: {
    fields: [
      {
        name: 'profile',
        label: 'Extension profile',
        hint: 'Name of the on-prem NtlmExtension profile declared under ' \
              '<code>extensions:</code> in the agent <code>config.yml</code>.',
        optional: false
      },
      {
        name: 'username',
        label: 'NTLM username',
        optional: false
      },
      {
        name: 'password',
        label: 'NTLM password',
        control_type: 'password',
        optional: false
      },
      {
        name: 'domain',
        label: 'NTLM domain',
        hint: 'Optional. Leave empty if the target does not require a domain.'
      },
      {
        name: 'workstation',
        label: 'NTLM workstation',
        hint: 'Optional. Ignored if <b>Domain</b> is empty.'
      }
    ],
    authorization: { type: 'none' }
  },

  methods: {
    # Builds the "username:password[@domain[/workstation]]" header value
    # expected by the NtlmExtension parser.
    ntlm_auth_header: ->(connection) {
      creds = "#{connection['username']}:#{connection['password']}"
      domain = connection['domain'].to_s.strip
      workstation = connection['workstation'].to_s.strip

      if domain.empty?
        creds
      elsif workstation.empty?
        "#{creds}@#{domain}"
      else
        "#{creds}@#{domain}/#{workstation}"
      end
    }
  },

  test: ->(connection) {
    # Verify the extension endpoint is mounted. We intentionally send a URL
    # that will not be contacted (localhost:1 is almost always refused) and
    # accept any 2xx/4xx/5xx — the extension itself answering means wiring
    # is correct. Returning raw so Workato doesn't try to parse JSON.
    post("http://localhost/ext/#{connection['profile']}/request")
      .headers(
        'X-Workato-NTLM-auth': call('ntlm_auth_header', connection),
        'X-Workato-NTLM-url': 'http://127.0.0.1:1/connection-test',
        'X-Workato-Connector': 'enforce'
      )
      .response_format_raw
  },

  actions: {
    get: {
      title: 'NTLM GET',
      subtitle: 'Fetch URL via NTLM auth',
      description: "Perform an <span class='provider'>NTLM</span>-authenticated " \
                   "<span class='provider'>GET</span> through the on-prem agent",

      input_fields: ->(_) {
        [
          {
            name: 'url',
            label: 'Target URL',
            hint: 'Absolute URL reachable from the on-prem agent host.',
            optional: false
          }
        ]
      },

      output_fields: ->(_) {
        [
          { name: 'body', label: 'Response body',
            hint: 'Upstream body on HTTP 200. Empty on any other status ' \
                  'or on transport errors (see agent log).' }
        ]
      },

      execute: ->(connection, input) {
        body = post("http://localhost/ext/#{connection['profile']}/request")
                 .headers(
                   'X-Workato-NTLM-auth': call('ntlm_auth_header', connection),
                   'X-Workato-NTLM-url': input['url'],
                   'X-Workato-Connector': 'enforce'
                 )
                 .response_format_raw
        { body: body }
      },

      sample_output: ->(_connection, _input) {
        { 'body' => '<html>...</html>' }
      }
    }
  }
}
