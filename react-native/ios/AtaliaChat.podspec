require 'json'

package = JSON.parse(File.read(File.join(__dir__, '..', 'package.json')))

# Um pod só: o SDK iOS (copiado de ios/Sources/AtaliaChat da raiz para ios/vendor pelo
# scripts/sync-ios-sdk.js) e a ponte do Expo. No app, `import AtaliaChat` dá acesso à API nativa.
Pod::Spec.new do |s|
  s.name           = 'AtaliaChat'
  s.version        = package['version']
  s.summary        = package['description']
  s.description    = package['description']
  s.license        = package['license']
  s.author         = package['author']
  s.homepage       = package['homepage']
  s.platforms      = { :ios => '15.1' }
  s.swift_version  = '5.9'
  s.source         = { git: 'https://github.com/Atalia-IA/atalia-mobile-sdk.git', tag: s.version.to_s }
  s.static_framework = true

  s.dependency 'ExpoModulesCore'

  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
  }

  s.source_files = '*.swift', 'vendor/AtaliaChat/**/*.swift'
  s.resource_bundles = { 'AtaliaChatPrivacy' => ['vendor/AtaliaChat/Resources/PrivacyInfo.xcprivacy'] }
end
