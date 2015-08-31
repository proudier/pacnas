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
mvn clean package
```

## How to Run 

Once built:

```
java -jar pacnas-$VERSION-fat.jar
```