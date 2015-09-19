# Pacnas: the ProActive Caching NAmeServer

## Introduction 

*What it is*

Pacnas is a recursive caching DNS server. Its peculiarity is that it does not drop a RR (Resource Record) when the TTL (Time To Live) is reached. Instead, it performs a new request to check the validity and -if necessary- update the cache.  

*What it is not*

Pacnas is a spare-time project I started to experience with various MPM (namely: thread pools and events based processing). It does not aim at making the world a better place; [BIND](https://www.isc.org/downloads/bind/) is probably what you're looking for.

*Thanks*

Thanks goes to:
* Brian Wellington for its [dnsjava](http://www.xbill.org/dnsjava/) library
* The [Vertx](http://vertx.io/) team 

## How to Build

Using Maven:

```
mvn clean package -Dmaven.test.skip=true
```

## How to Run 


### From IDE

Create a new launcher using as main class io.vertx.core.Starter
In the tab "Program Arguments" type: "run your.package.ServerVerticle

source: http://stackoverflow.com/questions/24277301/run-vertx-in-an-ide

### From command line

```
mvn vertx:runMod -Dmaven.test.skip=true
```

## Useful commands

dig @localhost -p5353 free.Fr +tries=1