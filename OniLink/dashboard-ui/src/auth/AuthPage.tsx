import { zodResolver } from "@hookform/resolvers/zod";
import { useEffect, useState } from "react";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { ApiError } from "../api/client";
import { dashboardApi } from "../api/dashboard";
import { BrandMark } from "../components/BrandMark";
import { Button, FieldError, Loading, Notice } from "../components/ui";
import { messageOf } from "../utilities/format";
import { useAuth } from "./AuthProvider";

const loginSchema = z.object({
  username: z.string().trim().min(1, "Username is required"),
  password: z.string().min(1, "Password is required"),
  totp: z.string().trim(),
});
const setupSchema = z.object({
  setupCode: z.string().trim().min(1, "Setup code is required"),
  username: z.string().trim().min(3, "Use at least 3 characters"),
  password: z.string().min(12, "Use at least 12 characters"),
});
type LoginValues = z.infer<typeof loginSchema>;
type SetupValues = z.infer<typeof setupSchema>;

export function AuthPage() {
  const { complete } = useAuth();
  const [setupRequired, setSetupRequired] = useState<boolean | null>(null);
  const [minimum, setMinimum] = useState(12);
  const [totpRequired, setTotpRequired] = useState(false);
  const [error, setError] = useState("");
  const login = useForm<LoginValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: { username: "", password: "", totp: "" },
  });
  const setup = useForm<SetupValues>({
    resolver: zodResolver(setupSchema),
    defaultValues: { setupCode: "", username: "", password: "" },
  });

  useEffect(() => {
    const controller = new AbortController();
    dashboardApi
      .setupStatus(controller.signal)
      .then((status) => {
        setSetupRequired(status.setupRequired);
        setMinimum(status.minimumPasswordLength);
      })
      .catch((reason: unknown) => setError(messageOf(reason)));
    return () => controller.abort();
  }, []);

  async function submitLogin(values: LoginValues) {
    setError("");
    try {
      complete(await dashboardApi.login(values));
    } catch (reason) {
      if (
        reason instanceof ApiError &&
        reason.data &&
        typeof reason.data === "object" &&
        (reason.data as { totpRequired?: boolean }).totpRequired
      )
        setTotpRequired(true);
      setError(
        reason instanceof ApiError && reason.kind === "rate-limited"
          ? "Too many attempts. Wait before trying again."
          : messageOf(reason),
      );
    }
  }

  async function submitSetup(values: SetupValues) {
    setError("");
    try {
      complete(await dashboardApi.setup(values));
    } catch (reason) {
      if (reason instanceof ApiError && reason.kind === "conflict") setSetupRequired(false);
      setError(messageOf(reason));
    }
  }

  if (setupRequired == null && !error)
    return (
      <main className="authPage">
        <Loading label="Checking control-plane setup" />
      </main>
    );
  const errors = setup.formState.errors;
  return (
    <main className="authPage">
      <section className="authBrand" aria-label="OniLink">
        <BrandMark />
        <p className="eyebrow">OniLink</p>
        <h1>Bedrock routing, under control.</h1>
        <p>
          Securely operate listeners, backends, connected players, and tenant proxies from one
          embedded control plane.
        </p>
        <div className="authTrust">
          <span>Same-origin API</span>
          <span>Role enforced</span>
          <span>Single JAR</span>
        </div>
      </section>
      <section className="authPanel">
        <div className="authFormWrap">
          <p className="eyebrow">Secure access</p>
          <h2>{setupRequired ? "Initialize the owner account" : "Sign in to OniLink"}</h2>
          <p>
            {setupRequired
              ? "Use the one-time code from the server console. It expires after setup."
              : "Your session is stored only in this browser tab."}
          </p>
          <Notice message={error} error />
          {setupRequired ? (
            <form onSubmit={setup.handleSubmit(submitSetup)} noValidate>
              <label>
                One-time setup code
                <input
                  autoComplete="off"
                  {...setup.register("setupCode")}
                  aria-describedby="setup-code-error"
                />
              </label>
              <FieldError id="setup-code-error">{errors.setupCode?.message}</FieldError>
              <label>
                Owner username
                <input
                  autoComplete="username"
                  {...setup.register("username")}
                  aria-describedby="setup-user-error"
                />
              </label>
              <FieldError id="setup-user-error">{errors.username?.message}</FieldError>
              <label>
                Owner password
                <input
                  type="password"
                  autoComplete="new-password"
                  {...setup.register("password")}
                  aria-describedby="setup-password-error"
                />
              </label>
              <FieldError id="setup-password-error">{errors.password?.message}</FieldError>
              <p className="fieldHint">
                Minimum {minimum} characters. This password is not recoverable from the dashboard.
              </p>
              <Button type="submit" disabled={setup.formState.isSubmitting}>
                {setup.formState.isSubmitting ? "Creating owner…" : "Create owner and sign in"}
              </Button>
            </form>
          ) : (
            <form onSubmit={login.handleSubmit(submitLogin)} noValidate>
              <label>
                Username
                <input
                  autoComplete="username"
                  {...login.register("username")}
                  aria-describedby="login-user-error"
                />
              </label>
              <FieldError id="login-user-error">
                {login.formState.errors.username?.message}
              </FieldError>
              <label>
                Password
                <input
                  type="password"
                  autoComplete="current-password"
                  {...login.register("password")}
                  aria-describedby="login-password-error"
                />
              </label>
              <FieldError id="login-password-error">
                {login.formState.errors.password?.message}
              </FieldError>
              {totpRequired ? (
                <label>
                  Authenticator code
                  <input
                    inputMode="numeric"
                    autoComplete="one-time-code"
                    maxLength={8}
                    {...login.register("totp")}
                  />
                </label>
              ) : null}
              <Button type="submit" disabled={login.formState.isSubmitting}>
                {login.formState.isSubmitting ? "Verifying…" : "Sign in"}
              </Button>
            </form>
          )}
        </div>
      </section>
    </main>
  );
}
