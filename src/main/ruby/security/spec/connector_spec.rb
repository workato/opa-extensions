# frozen_string_literal: true

RSpec.describe 'onprem security connector' do
  let(:settings) { Workato::Connector::Sdk::Settings.from_default_file }
  let(:connector) { Workato::Connector::Sdk::Connector.from_file('connector.rb', settings) }

  describe 'actions.sha256_digest' do
    it 'posts the payload to the agent and returns the signature it computed' do
      VCR.use_cassette('sha256_digest_hello') do
        output = connector.actions.sha256_digest.execute(settings, 'payload' => 'hello')
        expect(output).to be_a(Hash)
        expect(output['signature']).to match(/\A[0-9a-f]{64}\z/)
      end
    end

    it 'returns whatever signature the agent sends back' do
      VCR.use_cassette('sha256_digest_other') do
        output = connector.actions.sha256_digest.execute(settings, 'payload' => 'another')
        expect(output['signature']).to eq('1111111111111111111111111111111111111111111111111111111111111111')
      end
    end
  end

  describe 'test' do
    it 'pings the computeDigest endpoint without raising' do
      VCR.use_cassette('security_connection_test') do
        expect { connector.test(settings) }.not_to raise_error
      end
    end
  end
end
