# frozen_string_literal: true

RSpec.describe 'onprem ntlm connector' do
  let(:settings) { Workato::Connector::Sdk::Settings.from_default_file }
  let(:connector) { Workato::Connector::Sdk::Connector.from_file('connector.rb', settings) }

  describe 'methods.ntlm_auth_header' do
    it 'builds user:pass@domain/workstation when all fields are set' do
      expect(connector.methods.ntlm_auth_header(settings))
        .to eq('testuser:testpass@TESTDOMAIN/WS01')
    end

    it 'omits workstation when it is blank' do
      s = settings.merge('workstation' => '')
      expect(connector.methods.ntlm_auth_header(s))
        .to eq('testuser:testpass@TESTDOMAIN')
    end

    it 'omits both domain and workstation when domain is blank' do
      s = settings.merge('domain' => '', 'workstation' => '')
      expect(connector.methods.ntlm_auth_header(s))
        .to eq('testuser:testpass')
    end

    it 'ignores workstation when only workstation is set but domain is blank' do
      s = settings.merge('domain' => '', 'workstation' => 'WS42')
      expect(connector.methods.ntlm_auth_header(s))
        .to eq('testuser:testpass')
    end
  end

  describe 'actions.get' do
    it 'proxies the URL via the agent and returns the upstream body verbatim' do
      VCR.use_cassette('ntlm_get_ok') do
        output = connector.actions.get.execute(settings, 'url' => 'http://internal.example.com/data')
        expect(output).to eq('body' => 'upstream-ok')
      end
    end

    it 'returns whatever string the agent emits, including empty bodies' do
      VCR.use_cassette('ntlm_get_empty') do
        output = connector.actions.get.execute(settings, 'url' => 'http://internal.example.com/noop')
        expect(output).to eq('body' => '')
      end
    end
  end

  describe 'test' do
    it 'pings the agent endpoint without raising' do
      VCR.use_cassette('ntlm_connection_test') do
        expect { connector.test(settings) }.not_to raise_error
      end
    end
  end
end
