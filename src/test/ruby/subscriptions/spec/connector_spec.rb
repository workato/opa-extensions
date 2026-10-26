# frozen_string_literal: true
#
# How to run this spec
# --------------------
# From Gradle (works everywhere, uses the shared src/test/ruby/Gemfile):
#   ./gradlew :subscriptionsRspec                                      # whole file
#   ./gradlew :subscriptionsRspec -Pspec='inspect returns the extension view'  # by description
#   ./gradlew :subscriptionsRspec -Pspec=spec/connector_spec.rb:30     # by file:line
#
# From IDEA with the JetBrains Ruby plugin (Ultimate / RubyMine):
#   right-click the gutter triangle on describe/it. Make sure the RSpec
#   run-config template points Working directory at src/test/ruby/subscriptions
#   (VCR resolves cassette paths from CWD).
#
# From IDEA Community (no Ruby plugin): Run → Edit Configurations → + → Gradle,
#   Tasks: :subscriptionsRspec, Arguments: one of the -Pspec=... forms above.

CONNECTOR_PATH = File.expand_path('../../../../main/ruby/subscriptions/connector.rb', __dir__).freeze

RSpec.describe 'onprem subscriptions connector' do
  let(:settings) { Workato::Connector::Sdk::Settings.from_default_file }
  let(:connector) { Workato::Connector::Sdk::Connector.from_file(CONNECTOR_PATH, settings) }

  describe 'actions.inspect' do
    it 'calls the connection-scoped inspect endpoint and returns the extension view' do
      VCR.use_cassette('inspect_connection_42') do
        output = connector.actions.inspect.execute(settings, {})
        expect(output).to be_a(Hash)
        expect(output['adapter']).to eq('subscriptions')
        expect(output['connectionId']).to eq(42)
        expect(output['staticPerAdapter']['environment']).to eq('prod')
        expect(output['connection']['label-via-supplier']).to eq('primary')
        expect(output['lifecycle']['addedTotal']).to eq(1)
        expect(output['lifecycle']['trackedConnections']).to include(42)
      end
    end

    it 'surfaces the lifecycle update counter the extension collected' do
      VCR.use_cassette('inspect_after_updates') do
        output = connector.actions.inspect.execute(settings, {})
        expect(output['lifecycle']['updatedTotal']).to eq(3)
        expect(output['lifecycle']['thisConnection']['updateCount']).to eq(3)
      end
    end
  end

  describe 'test' do
    it 'pings the inspect endpoint without raising' do
      VCR.use_cassette('subscriptions_connection_test') do
        expect { connector.test(settings) }.not_to raise_error
      end
    end
  end
end
