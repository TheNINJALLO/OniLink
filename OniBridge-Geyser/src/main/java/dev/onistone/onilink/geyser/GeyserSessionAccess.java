package dev.onistone.onilink.geyser;

import dev.onistone.onilink.geyser.forwarding.TrustedProxyMatcher;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/** Isolates the two version-sensitive Geyser core calls that are not exposed by the extension API. */
final class GeyserSessionAccess {
    record LoginData(InetSocketAddress sourceAddress, String clientDataJwt) {
    }

    LoginData read(Object connection) throws ReflectiveOperationException {
        Object upstream = invoke(connection, "getUpstream");
        Object rawAddress = invoke(upstream, "getAddress");
        if (!(rawAddress instanceof InetSocketAddress sourceAddress) || sourceAddress.getAddress() == null) {
            throw new IllegalStateException("Geyser returned a non-Internet source address");
        }
        Object clientData = invoke(connection, "getClientData");
        Object originalString = invoke(clientData, "getOriginalString");
        if (!(originalString instanceof String jwt) || jwt.isBlank()) {
            throw new IllegalStateException("Geyser did not preserve the client data JWT");
        }
        return new LoginData(sourceAddress, jwt);
    }

    void applyRealAddress(Object connection, String ip, int port) throws ReflectiveOperationException {
        InetAddress address = TrustedProxyMatcher.parseLiteral(ip);
        if (address == null) {
            throw new IllegalArgumentException("verified address is not an IP literal");
        }
        Object upstream = invoke(connection, "getUpstream");
        Method setter = upstream.getClass().getMethod("setInetAddress", InetSocketAddress.class);
        invoke(setter, upstream, new InetSocketAddress(address, port));
    }

    private static Object invoke(Object target, String method) throws ReflectiveOperationException {
        return invoke(target.getClass().getMethod(method), target);
    }

    private static Object invoke(Method method, Object target, Object... arguments) throws ReflectiveOperationException {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof ReflectiveOperationException reflection) {
                throw reflection;
            }
            throw new IllegalStateException("Geyser internal call failed", cause);
        }
    }
}
