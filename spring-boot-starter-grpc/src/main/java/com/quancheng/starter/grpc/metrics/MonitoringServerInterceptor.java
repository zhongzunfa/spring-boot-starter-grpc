package com.quancheng.starter.grpc.metrics;

import java.time.Clock;

import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

public class MonitoringServerInterceptor implements ServerInterceptor {

    private final Clock                 clock;
    private final MetricsConfiguration         configuration;
    private final ServerMetrics.Factory serverMetricsFactory;

    public static MonitoringServerInterceptor create(MetricsConfiguration configuration) {
        return new MonitoringServerInterceptor(Clock.systemDefaultZone(), configuration,
                                               new ServerMetrics.Factory(configuration));
    }

    private MonitoringServerInterceptor(Clock clock, MetricsConfiguration configuration,
                                        ServerMetrics.Factory serverMetricsFactory){
        this.clock = clock;
        this.configuration = configuration;
        this.serverMetricsFactory = serverMetricsFactory;
    }

    @Override
    public <R, S> ServerCall.Listener<R> interceptCall(ServerCall<R, S> call, Metadata requestHeaders,
                                                       ServerCallHandler<R, S> next) {
        MethodDescriptor<R, S> method = call.getMethodDescriptor();
        ServerMetrics metrics = serverMetricsFactory.createMetricsForMethod(method);
        GrpcMethod grpcMethod = GrpcMethod.of(method);
        ServerCall<R, S> monitoringCall = new MonitoringServerCall(call, clock, grpcMethod, metrics, configuration);
        return new MonitoringServerCallListener<>(next.startCall(monitoringCall, requestHeaders), metrics,
                                                  GrpcMethod.of(method));
    }

}
