package com.quancheng.starter.grpc;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessorAdapter;

import com.google.common.base.Preconditions;
import com.quancheng.starter.grpc.autoconfigure.GRpcServerProperties;
import com.quancheng.starter.grpc.internal.ConsulNameResolver;
import com.quancheng.starter.grpc.trace.TraceClientInterceptor;

import io.grpc.Attributes;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.LoadBalancer;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.NameResolver;
import io.grpc.util.RoundRobinLoadBalancerFactory;

public class GRpcReferenceRunner extends InstantiationAwareBeanPostProcessorAdapter {

    private final GRpcServerProperties gRpcServerProperties;

    public GRpcReferenceRunner(GRpcServerProperties gRpcServerProperties){
        this.gRpcServerProperties = gRpcServerProperties;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        Class<?> searchType = bean.getClass();
        while (!Object.class.equals(searchType) && searchType != null) {
            Field[] fields = searchType.getDeclaredFields();
            for (Field field : fields) {
                GRpcReference reference = field.getAnnotation(GRpcReference.class);
                if (reference != null) {
                    String serviceName = reference.serviceName();
                    Channel channel = this.generateChannel(serviceName);
                    String clzzName = field.getType().getName();
                    // 没有破坏pb的生成规则
                    if (clzzName.contains("$")) {
                        try {
                            String parentName = StringUtils.substringBefore(clzzName, "$");
                            Class clzz = Class.forName(parentName);
                            Method method;
                            switch (reference.callType()) {
                                case "future":
                                    method = clzz.getMethod("newFutureStub", io.grpc.Channel.class);
                                    break;
                                case "blocking":
                                    method = clzz.getMethod("newBlockingStub", io.grpc.Channel.class);
                                    break;
                                case "async":
                                    method = clzz.getMethod("newBlockingStub", io.grpc.Channel.class);
                                    break;
                                default:
                                    method = clzz.getMethod("newFutureStub", io.grpc.Channel.class);
                                    break;
                            }
                            Object value = method.invoke(null, channel);
                            if (!field.isAccessible()) {
                                field.setAccessible(true);
                            }
                            field.set(bean, value);
                        } catch (Exception e) {
                            throw new IllegalArgumentException("stub definition not correct，do not edit proto generat file",
                                                               e);
                        }
                    } else {
                        throw new IllegalArgumentException("stub definition not correct，do not edit proto generat file");
                    }

                }
            }
            searchType = searchType.getSuperclass();
        }
        return bean;
    }

    private Channel generateChannel(String serviceName) {
        String consulUrl = "consul:///" + gRpcServerProperties.getConsulIp() + ":"
                           + gRpcServerProperties.getConsulPort();
        ManagedChannel channel = ManagedChannelBuilder.forTarget(consulUrl)//
                                                      .nameResolverFactory(buildNameResolverFactory(serviceName))//
                                                      .loadBalancerFactory(buildLoadBalanceFactory()).usePlaintext(true).build();//
        Channel channelWrap = ClientInterceptors.intercept(channel, new TraceClientInterceptor());
        return channelWrap;
    }

    private NameResolver.Factory buildNameResolverFactory(String serviceName) {
        final Attributes attributesParams = Attributes.newBuilder().set(ConsulNameResolver.PARAMS_DEFAULT_SERVICESNAME,
                                                                        serviceName).build();
        return new NameResolver.Factory() {

            @Override
            public NameResolver newNameResolver(URI targetUri, Attributes params) {
                String targetPath = Preconditions.checkNotNull(targetUri.getPath(), "targetPath");
                Preconditions.checkArgument(targetPath.startsWith("/"),
                                            "the path component (%s) of the target (%s) must start with '/'",
                                            targetPath, targetUri);
                String name = targetPath.substring(1);
                Attributes allParams = Attributes.newBuilder().setAll(attributesParams).setAll(params).build();
                return new ConsulNameResolver(targetUri.getAuthority(), name, allParams);
            }

            @Override
            public String getDefaultScheme() {
                return "consul";
            }

        };
    }

    private LoadBalancer.Factory buildLoadBalanceFactory() {
        return RoundRobinLoadBalancerFactory.getInstance();
    }
}