FROM ubuntu:20.04
RUN apt-get update
RUN apt-get install -y g++ make curl jq libzmqpp-dev

RUN curl -sS https://storage.googleapis.com/kubernetes-release/release/v1.18.0/bin/linux/amd64/kubectl > /usr/local/bin/kubectl
RUN chmod +x /usr/local/bin/kubectl

ENV UMLRTS_ROOT /umlrts
COPY ./umlrts /umlrts
WORKDIR /umlrts
RUN make