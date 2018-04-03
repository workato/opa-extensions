
OPA extension SDK allows customers, external developers/partners to connect to legacy on-prem systems using Java within the Workato Agent infrastructure.


## Configuration

Extension mechanism is enabled by adding the `extensions` section to the config file, `conf/config.yml`:

```yml
    server:
      classpath: C:\\Program Files\\Workato Agent\\ext
    extensions:
      security:
        controllerClass: com.mycompany.onprem.SecurityExtension
        secret: HA63A3043AMMMM
```

Individual endpoints are defined inside `extensions`, where every key serves as _endpoint name_. Every _endpoint name_ has to be valid identifier, has to be URL-friendly and cannot contain any special characters (like whitespace, etc.).

Classpath defines a set of folders/JAR files containing compiled extensions.


## Using extension endpoints

Every endpoint is hosted inside the REST-compliant URL: `/ext/<endpointName>/<resource>` where `<endpointName>` corresponds to key defined in the configuration file and `<resource>` is an specific REST resource within the endpoint.

Endpoint request handling logic is defined by Spring REST controller class, provided as `controllerClass` property. This controller class is registered inside an endpoint-specific Spring `WebApplicationContext` and allows retrieving configuration properties from the `config.yml` file.

## Endpoint controller sample

Full code can be found [here](https://github.com/workato/opa-extensions/blob/master/src/main/java/com/mycompany/onprem/SecurityExtension.java).

```java
package com.mycompany.onprem;

// import rest of the packages here:
@Controller
public class SecurityExtension {

    @Inject
    private Environment env;

    @RequestMapping(method = RequestMethod.POST)
    public @ResponseBody Map<String, Object> computeDigest(@RequestBody Map<String, Object> body) throws Exception {
        Charset encoding = Charset.forName("UTF-8");
        String payload = (String) body.get("payload");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(env.getProperty("secret").getBytes(encoding));
        byte[] result = digest.digest(payload.getBytes(encoding));
        return Collections.singletonMap("signature", Hex.encodeHexString(result));
    }
}
```

Given the configuration:

```yml
    extensions:
      security:
        controllerClass: com.mycompany.onprem.SecurityExtension
        secret: HA63A3043AMMMM 
```

## Building extension

Steps to build an extension:

1. Install the latest Java 8 SDK
2. Use `./gradlew jar` command to bootstrap Gradle and build the project.
3. The output is in `build/libs`.

## Installing the extension to OPA

1. Add a new directory called `ext` under Workato agent install directory.
2. Copy the extension JAR file to `ext` directory.
3. Update the `config/config.yml` to add the `ext` file to class path.

```yml
    server:
      classpath: C:\\Program Files\\Workato Agent\\ext
```

4. Update the `config/config.yml` to configure the new extension.

```yml
    extensions:
      security:
        controllerClass: com.mycompany.onprem.SecurityExtension
        secret: HA63A3043AMMMM
```


## Using the extension in Recipe

In order to use the extension in a recipe, we need a custom adapter in Workato. Sample adapter for the extension 
can be found [here](https://github.com/workato/connector_sdk/blob/master/basic_auth/onprem_security.rb).

```ruby
{
  title: 'On-prem security',
  secure_tunnel: true,

  connection: {
    fields: [{ name: 'profile', hint: 'On-prem security connection profile' }],
    authorization: { type: 'none'}
  },

  test: ->(connection) {
    post("http://localhost/ext/#{connection['profile']}/computeDigest", { payload: 'test' }).headers('X-Workato-Connector': 'enforce')
  },

  actions: {
    sha256_digest: {

      title: 'Create SHA-256 digest',
      description: 'Create <span class="provider">SHA-256</span> digest',

      input_fields: ->(_) { [{ name: 'payload' }] },
      output_fields: ->(_) { [{name: 'signature'}] },

      execute: ->(connection, input) {
        post("http://localhost/ext/#{connection['profile']}/computeDigest", input).headers('X-Workato-Connector': 'enforce')
      }
    }
  }
}
```
