package io.configd.server.fanout;

import io.configd.distribution.fanout.SlowConsumerGovernor;

import java.io.IOException;


public interface FanOutEndpoint {

    
    void start() throws IOException;

    
    int localPort();

    
    void close();

    
    SlowConsumerGovernor governor();
}
