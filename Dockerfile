
FROM ubuntu
RUN apt-get update && apt-get install wget -y
RUN mkdir /usr/app
WORKDIR /usr/app
#RUN cd workspace
COPY /maven-dependency-plugin/target/maven-dependency-plugin-3.8.2-SNAPSHOT.jar /usr/app
