
OPA extension SDK allows customers, external developers/partners to connect to legacy on-prem systems using Java within the Workato Agent infrastructure.


## Configuration

Extension mechanism is enabled by adding the `extensions` section to the config file, `conf/config.yml`:

```yml
    server:
      classpath: C:\\Program Files\\Workato Agent\\ext
    extensions:
      ldap:
        controllerClass: com.mycompany.ldap.LdapController
        host: ldap.mycompany.intra
          ...
```

Individual endpoints are defined inside `extensions`, where every key serves as _endpoint name_. Every _endpoint name_ has to be valid identifier, has to be URL-friendly and cannot contain any special characters (like whitespace, etc.).

Classpath defines a set of folders/JAR files containing compiled extensions.

**NOTE:** extension classpath also applies to loading custom JDBC drivers.

## Using extension endpoints

Every endpoint is hosted inside the REST-compliant URL: `/ext/<endpointName>/<resource>` where `<endpointName>` corresponds to key defined in the configuration file and `<resource>` is an specific REST resource within the endpoint.

Endpoint request handling logic is defined by Spring REST controller class, provided as `controllerClass` property. This controller class is registered inside an endpoint-specific Spring `WebApplicationContext` and allows retrieving configuration properties from the `config.yml` file.

**NOTE:** the agent port may vary depending on configuration. To use endpoints externally user has to pin-down port number, eg. by command-line option or configuration property.

## Endpoint controller sample

```java
    package com.mycompany.hello;

    import ...

    @Controller
    public class HelloWorld {

        @Inject
        private Environment env;

        @RequestMapping(method = RequestMethod.GET)
        public @ResponseBody List<Object> hello() {
            return Arrays.asList(env.getProperty("myConfigProperty"));
        }
    }
```

Given the configuration:

```yml
    extensions:
      hello:
        controllerClass: com.mycompany.hello.HelloWorld
        myConfigProperty: myConfigValue
 ```

## Building extension

Steps to build an extension:

1. Install the latest Java 8 SDK
1. Use `./gradlew jar` command to bootstrap Gradle and build the project.
1. The output is in `build/libs`.

