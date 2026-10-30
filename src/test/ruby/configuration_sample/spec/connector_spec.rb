# frozen_string_literal: true
#
# How to run this spec
# --------------------
# From Gradle (works everywhere, uses the shared src/test/ruby/Gemfile):
#   ./gradlew :configurationSampleRspec                                       # whole file
#   ./gradlew :configurationSampleRspec -Pspec='returns the extension info'   # by description
#   ./gradlew :configurationSampleRspec -Pspec=spec/connector_spec.rb:30      # by file:line
#
# From IDEA with the JetBrains Ruby plugin (Ultimate / RubyMine):
#   right-click the gutter triangle on describe/it. Make sure the RSpec
#   run-config template points Working directory at src/test/ruby/configuration_sample
#   (VCR resolves cassette paths from CWD).
#
# From IDEA Community (no Ruby plugin): Run → Edit Configurations → + → Gradle,
#   Tasks: :configurationSampleRspec, Arguments: one of the -Pspec=... forms above.

CONNECTOR_PATH = File.expand_path('../../../../main/ruby/configuration_sample/connector.rb', __dir__).freeze

RSpec.describe 'onprem configuration_sample connector' do
  let(:settings) { Workato::Connector::Sdk::Settings.from_default_file }
  let(:connector) { Workato::Connector::Sdk::Connector.from_file(CONNECTOR_PATH, settings) }

  describe 'actions.get_info' do
    it 'calls the /info endpoint and returns static + cloud params and both timestamps' do
      VCR.use_cassette('get_info_connection_42') do
        output = connector.actions.get_info.execute(settings, {})
        expect(output).to be_a(Hash)
        expect(output['environment']).to eq('prod')
        expect(output['timeout']).to eq(5000)
        expect(output['updatedAt']).to eq('2026-10-30T12:05:00Z')
        expect(output['createdAt']).to eq('2026-10-30T12:00:00Z')
      end
    end
  end

  describe 'test' do
    it 'passes when the extension returns 200' do
      VCR.use_cassette('configuration_sample_connection_test_ok') do
        expect { connector.test(settings) }.not_to raise_error
      end
    end

    it "surfaces the extension's error message when the 400 body carries one" do
      VCR.use_cassette('configuration_sample_connection_test_fail') do
        expect { connector.test(settings) }.to raise_error(/timeout must be > 0/)
      end
    end
  end
end
