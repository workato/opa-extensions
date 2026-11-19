# frozen_string_literal: true

require 'workato-connector-sdk'
require 'webmock/rspec'
require 'vcr'

VCR.configure do |c|
  # Shared across every src/test/ruby/<name>/ connector project. The CWD is
  # the per-connector directory (set by Gradle `workingDir` or by running
  # `cd src/test/ruby/<name> && bundle exec rspec` locally); its basename
  # matches the subdir under src/test/resources/tape_library/ that holds this
  # connector's cassettes.
  c.cassette_library_dir =
    File.expand_path("../../resources/tape_library/#{File.basename(Dir.pwd)}", Dir.pwd)
  c.hook_into :webmock
  c.default_cassette_options = { record: :none }
end

RSpec.configure do |c|
  c.expect_with :rspec do |e|
    e.syntax = :expect
  end
end
