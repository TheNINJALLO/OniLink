import { useQueryClient } from "@tanstack/react-query";
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type PropsWithChildren,
} from "react";
import { dashboardApi } from "../api/dashboard";
import { clearToken, getToken, setToken } from "../api/client";
import type { Principal, SessionPayload } from "../types/dashboard";

interface AuthContextValue {
  principal: Principal | null;
  restoring: boolean;
  complete: (session: SessionPayload) => void;
  signOut: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: PropsWithChildren) {
  const queryClient = useQueryClient();
  const [principal, setPrincipal] = useState<Principal | null>(null);
  const [restoring, setRestoring] = useState(Boolean(getToken()));
  const invalidate = useCallback(() => {
    clearToken(false);
    setPrincipal(null);
    setRestoring(false);
    queryClient.clear();
  }, [queryClient]);

  useEffect(() => {
    window.addEventListener("onilink:session-invalid", invalidate);
    return () => window.removeEventListener("onilink:session-invalid", invalidate);
  }, [invalidate]);

  useEffect(() => {
    if (!getToken()) return;
    const controller = new AbortController();
    dashboardApi
      .whoami(controller.signal)
      .then(setPrincipal)
      .catch((error: unknown) => {
        if (!(error instanceof DOMException && error.name === "AbortError")) invalidate();
      })
      .finally(() => setRestoring(false));
    return () => controller.abort();
  }, [invalidate]);

  const complete = useCallback((session: SessionPayload) => {
    setToken(session.token);
    setPrincipal({ username: session.username, role: session.role, tenantId: session.tenantId });
    setRestoring(false);
  }, []);

  const signOut = useCallback(async () => {
    try {
      await dashboardApi.logout();
    } catch {
      /* Local logout must still succeed. */
    }
    invalidate();
  }, [invalidate]);

  const value = useMemo(
    () => ({ principal, restoring, complete, signOut }),
    [principal, restoring, complete, signOut],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) throw new Error("useAuth must be used inside AuthProvider");
  return value;
}
