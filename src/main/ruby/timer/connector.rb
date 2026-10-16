# Workato custom connector for the TimerExtension on-prem agent extension.
#
# Companion for: src/main/java/com/mycompany/onprem/timer/TimerExtension.java
# Agent endpoints (all under /ext/{profile}/webhook/):
#   GET  health      — liveness check (used by the connection test lambda)
#   POST subscribe   — register a periodic timer, returns {"id": "<uuid>"}
#   POST unsubscribe — cancel a timer by its subscription id
#   POST fire_now    — synchronously emit one webhook event for a subscription
#
# Demonstrates AgentWebhookApiV1 + the Workato SDK webhook_subscribe /
# webhook_notification / webhook_unsubscribe trigger pattern. Single-node /
# in-memory on the agent side (see PLAN.md).

{
  title: 'On-prem timer (webhook demo)',
  secure_tunnel: true,

  connection: {
    fields: [
      {
        name: 'profile',
        label: 'Extension profile',
        hint: 'Name of the on-prem TimerExtension profile declared under ' \
          '<code>extensions:</code> in the agent <code>config.yml</code>.',
        optional: false
      },
      {
        name: 'connection_id',
        label: 'Connection ID',
        hint: 'A stable identifier for this connection. Threaded through to ' \
          'the extension on subscribe/unsubscribe so the agent can key ' \
          'subscriptions per connection.',
        optional: false
      }
    ],

    authorization: {
      type: 'custom_auth',
      acquire: lambda do |_connection|
        {}
      end,
      apply: lambda do |_connection|
        headers('X-Workato-Connector': 'enforce')
      end
    },

    base_uri: lambda do |connection|
      "http://localhost/ext/#{connection['profile']}/"
    end
  },

  test: lambda do |_connection|
    get('webhook/health').
      after_error_response(/.*/) do |_code, body, _headers, message|
      error("Connection test failed: #{message}. Response: #{body}")
    end
  end,

  object_definitions: {
    timer_event: {
      fields: lambda do |_connection, _config_fields, _object_definitions|
        [
          { name: 'message', label: 'Message', type: 'string' },
          { name: 'generated_at', label: 'Generated at', type: 'date_time' },
          { name: 'tick', label: 'Tick', type: 'integer' },
          { name: 'subscription_id', label: 'Subscription ID', type: 'string' },
          { name: 'period_seconds', label: 'Period (seconds)', type: 'integer' }
        ]
      end
    }
  },

  actions: {
    fire_now: {
      title: 'Fire now',
      subtitle: 'Emit one webhook event for a subscription immediately',
      description: "Synchronously emit one <span class='provider'>timer</span> event " \
        "to a subscribed webhook URL",

      input_fields: lambda do |_object_definitions|
        [
          { name: 'subscription_id', label: 'Subscription ID',
            hint: 'The id returned by webhook_subscribe (or logged by the extension).',
            optional: false }
        ]
      end,

      output_fields: lambda do |_object_definitions|
        [
          { name: 'delivered', label: 'Delivered', type: 'boolean' },
          { name: 'status_code', label: 'Status code', type: 'integer' }
        ]
      end,

      execute: lambda do |_connection, input|
        post('webhook/fire_now').payload(subscription_id: input['subscription_id']).
          after_error_response(/.*/) do |_code, body, _headers, message|
          error("fire_now failed: #{message}. Response: #{body}")
        end
      end,

      sample_output: lambda do |_connection, _input|
        { 'delivered' => true, 'status_code' => 200 }
      end
    }
  },

  triggers: {
    tick: {
      title: 'New timer tick',
      subtitle: 'Fires every N seconds with a user-defined message',
      description: "New <span class='provider'>tick</span> from the " \
        "<span class='provider'>on-prem timer</span>",
      help: 'The extension pushes one event every <code>period_seconds</code> ' \
        'to the Workato-provided webhook URL. Minimum period is enforced ' \
        'by the agent (default 5 seconds in production).',

      input_fields: lambda do |_object_definitions|
        [
          { name: 'message', label: 'Message',
            hint: 'The string the extension echoes back on every tick.',
            optional: false },
          { name: 'period_seconds', label: 'Period (seconds)',
            type: 'integer', default: 10,
            hint: 'Minimum 5 in production — the extension rejects smaller values.',
            optional: false }
        ]
      end,

      webhook_subscribe: lambda do |webhook_url, connection, input, recipe_id|
        response = post('webhook/subscribe').payload(
          workato_webhook_url: webhook_url,
          recipe_id: recipe_id,
          connection_id: connection['connection_id'],
          message: input['message'],
          period_seconds: input['period_seconds']
        ).after_error_response(/.*/) do |_code, body, _headers, message|
          error("Failed to subscribe webhook: #{message}. Response: #{body}")
        end
        # Cache connection_id in the subscribe output so webhook_unsubscribe
        # (which the SDK calls with just the subscribe output, no connection)
        # can tear the subscription down without a side-channel lookup.
        response.merge('connection_id' => connection['connection_id'])
      end,

      webhook_notification: lambda do |_input, payload, _ext_input_schema, _ext_output_schema, _headers, _params, _settings, _subscribe_output|
        payload
      end,

      webhook_unsubscribe: lambda do |webhook_subscribe_output|
        post('webhook/unsubscribe').payload(
          recipe_id: webhook_subscribe_output['id'],
          connection_id: webhook_subscribe_output['connection_id']
        ).after_error_response(/.*/) do |_code, body, _headers, message|
          error("Failed to unsubscribe webhook: #{message}. Response: #{body}")
        end
      end,

      dedup: lambda do |payload|
        "#{payload['subscription_id']}-#{payload['tick']}"
      end,

      output_fields: lambda do |object_definitions|
        object_definitions['timer_event']
      end,

      sample_output: lambda do |_connection, _input|
        {
          'message' => 'hello',
          'generated_at' => '2026-10-16T14:03:21Z',
          'tick' => 1,
          'subscription_id' => '0000-aaaa',
          'period_seconds' => 10
        }
      end
    }
  }
}
