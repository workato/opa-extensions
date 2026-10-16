# frozen_string_literal: true
#
# How to run this spec
# --------------------
# From Gradle (works everywhere, uses the shared src/test/ruby/Gemfile):
#   ./gradlew :timerRspec                                             # whole file
#   ./gradlew :timerRspec -Pspec='fire_now'                           # one example by description
#   ./gradlew :timerRspec -Pspec=spec/connector_spec.rb:12            # one example by file:line
#
# From IDEA with the JetBrains Ruby plugin (Ultimate / RubyMine):
#   right-click the gutter triangle on describe/it. Make sure the RSpec
#   run-config template points Working directory at src/test/ruby/timer
#   (VCR resolves cassette paths from CWD).
#
# From IDEA Community (no Ruby plugin): Run → Edit Configurations → + → Gradle,
#   Tasks: :timerRspec, Arguments: one of the -Pspec=... forms above.

CONNECTOR_PATH = File.expand_path('../../../../main/ruby/timer/connector.rb', __dir__).freeze

RSpec.describe 'onprem timer connector' do
  let(:settings) { Workato::Connector::Sdk::Settings.from_default_file }
  let(:connector) { Workato::Connector::Sdk::Connector.from_file(CONNECTOR_PATH, settings) }

  describe 'test' do
    it 'pings the health endpoint without raising' do
      VCR.use_cassette('test') do
        expect { connector.test(settings) }.not_to raise_error
      end
    end
  end

  describe 'actions.fire_now' do
    it 'posts the subscription id and returns the delivered/status pair' do
      VCR.use_cassette('fire_now') do
        output = connector.actions.fire_now.execute(settings, 'subscription_id' => 'sub-abc')
        expect(output).to be_a(Hash)
        expect(output['delivered']).to be(true)
        expect(output['status_code']).to eq(200)
      end
    end
  end

  describe 'triggers.tick' do
    it 'webhook_subscribe posts the subscription body and returns {id, connection_id}' do
      VCR.use_cassette('subscribe') do
        output = connector.triggers.tick.webhook_subscribe(
          'https://recipe/wh',
          settings,
          { 'message' => 'hi', 'period_seconds' => 10 },
          'recipe-xyz'
        )
        expect(output['id']).to match(/\A[0-9a-f-]{36}\z/)
        # connection_id is cached into the subscribe output so webhook_unsubscribe
        # can access it later (SDK does not re-supply connection on unsubscribe).
        expect(output['connection_id']).to eq('test-conn')
      end
    end

    it 'webhook_notification returns the payload hash as the event' do
      payload = {
        'tick' => 1,
        'message' => 'hi',
        'subscription_id' => 'sub-abc',
        'period_seconds' => 10,
        'generated_at' => '2026-10-16T14:03:21Z'
      }
      event = connector.triggers.tick.webhook_notification(
        {}, payload, [], [], {}, {}, nil, {}
      )
      expect(event).to eq(payload)
    end

    it 'webhook_unsubscribe posts recipe_id (= subscription id) and connection_id' do
      VCR.use_cassette('unsubscribe') do
        expect {
          connector.triggers.tick.webhook_unsubscribe(
            { 'id' => 'sub-abc', 'connection_id' => 'test-conn' }
          )
        }.not_to raise_error
      end
    end

    it 'dedup is deterministic from subscription_id + tick (no wall clock)' do
      key1 = connector.triggers.tick.dedup({ 'subscription_id' => 'sub-abc', 'tick' => 7 })
      key2 = connector.triggers.tick.dedup({ 'subscription_id' => 'sub-abc', 'tick' => 7 })
      expect(key1).to eq('sub-abc-7')
      expect(key1).to eq(key2)
    end
  end
end
