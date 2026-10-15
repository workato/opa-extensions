# frozen_string_literal: true

require 'workato-connector-sdk'
require 'webmock/rspec'
require 'vcr'

VCR.configure do |c|
  c.cassette_library_dir = File.expand_path('../tape_library', __dir__)
  c.hook_into :webmock
  c.default_cassette_options = { record: :none }
end

RSpec.configure do |c|
  c.expect_with :rspec do |e|
    e.syntax = :expect
  end
end
