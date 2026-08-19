import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { KeyRound, ShieldCheck, Trash2, UserPlus } from "lucide-react";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { clearToken } from "../../api/client";
import { dashboardApi } from "../../api/dashboard";
import { useAuth } from "../../auth/AuthProvider";
import { Button, Card, FieldError, Notice, PageHeader, Status } from "../../components/ui";
import { ConfirmDialog } from "../../components/ConfirmDialog";
import type { DashboardUser } from "../../types/dashboard";
import { messageOf } from "../../utilities/format";

const passwordSchema = z.object({
  currentPassword: z.string().min(1, "Current password is required"),
  newPassword: z.string().min(12, "Use at least 12 characters"),
});
const userSchema = z.object({
  username: z.string().trim().min(3).max(64),
  password: z.string().min(12),
  role: z.enum(["viewer", "operator", "admin"]),
});
type PasswordValues = z.infer<typeof passwordSchema>;
type UserValues = z.infer<typeof userSchema>;

export function AccountPage() {
  const { principal } = useAuth();
  const client = useQueryClient();
  const owner = principal?.role === "owner";
  const users = useQuery({
    queryKey: ["users"],
    queryFn: ({ signal }) => dashboardApi.users(signal),
    enabled: owner,
  });
  const passwordForm = useForm<PasswordValues>({
    resolver: zodResolver(passwordSchema),
    defaultValues: { currentPassword: "", newPassword: "" },
  });
  const userForm = useForm<UserValues>({
    resolver: zodResolver(userSchema),
    defaultValues: { username: "", password: "", role: "viewer" },
  });
  const [message, setMessage] = useState("");
  const [totp, setTotp] = useState<{ secret: string; uri: string } | null>(null);
  const [totpCode, setTotpCode] = useState("");
  const [disablePassword, setDisablePassword] = useState("");
  const [disableCode, setDisableCode] = useState("");
  const [remove, setRemove] = useState<DashboardUser | null>(null);
  const endSession = () => {
    clearToken();
  };
  const password = useMutation({ mutationFn: dashboardApi.changePassword, onSuccess: endSession });
  const begin = useMutation({ mutationFn: dashboardApi.beginTotp, onSuccess: setTotp });
  const enable = useMutation({
    mutationFn: () => dashboardApi.enableTotp(totp?.secret ?? "", totpCode),
    onSuccess: endSession,
  });
  const disable = useMutation({
    mutationFn: () => dashboardApi.disableTotp(disablePassword, disableCode),
    onSuccess: endSession,
  });
  const create = useMutation({
    mutationFn: dashboardApi.createUser,
    onSuccess: async () => {
      userForm.reset();
      setMessage("Dashboard account created.");
      await client.invalidateQueries({ queryKey: ["users"] });
    },
  });
  const drop = useMutation({
    mutationFn: dashboardApi.deleteUser,
    onSuccess: async () => {
      setRemove(null);
      setMessage("Dashboard account removed.");
      await client.invalidateQueries({ queryKey: ["users"] });
    },
  });
  const activeError =
    password.error ?? begin.error ?? enable.error ?? disable.error ?? create.error ?? drop.error;
  return (
    <>
      <PageHeader
        title="Account"
        description="Manage your dashboard credentials and security factors."
        actions={
          principal ? (
            <Status state="neutral">
              {principal.username} · {principal.role}
            </Status>
          ) : null
        }
      />
      <Notice message={message} />
      <Notice message={activeError ? messageOf(activeError) : ""} error />
      <div className="twoColumn">
        <Card>
          <div className="sectionTitle">
            <div>
              <p className="eyebrow">Credentials</p>
              <h2>Change password</h2>
            </div>
            <KeyRound aria-hidden="true" />
          </div>
          <p className="fieldHint">
            A successful change ends every dashboard session, including this one.
          </p>
          <form
            onSubmit={passwordForm.handleSubmit((values) => password.mutate(values))}
            noValidate
          >
            <label>
              Current password
              <input
                type="password"
                autoComplete="current-password"
                {...passwordForm.register("currentPassword")}
              />
            </label>
            <FieldError id="current-password-error">
              {passwordForm.formState.errors.currentPassword?.message}
            </FieldError>
            <label>
              New password
              <input
                type="password"
                autoComplete="new-password"
                {...passwordForm.register("newPassword")}
              />
            </label>
            <FieldError id="new-password-error">
              {passwordForm.formState.errors.newPassword?.message}
            </FieldError>
            <Button type="submit" disabled={password.isPending}>
              {password.isPending ? "Changing…" : "Change password"}
            </Button>
          </form>
        </Card>
        <Card>
          <div className="sectionTitle">
            <div>
              <p className="eyebrow">Authenticator</p>
              <h2>Two-step verification</h2>
            </div>
            <ShieldCheck aria-hidden="true" />
          </div>
          {totp ? (
            <>
              <div className="warningBox">
                <strong>Enrollment secret</strong>
                <span>Keep this private. It is held only until you leave this view.</span>
              </div>
              <code className="secretValue">{totp.secret}</code>
              <p className="breakText">{totp.uri}</p>
              <label>
                Six-digit code
                <input
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  value={totpCode}
                  onChange={(event) => setTotpCode(event.target.value)}
                />
              </label>
              <Button disabled={enable.isPending || !totpCode} onClick={() => enable.mutate()}>
                Verify and enable
              </Button>
            </>
          ) : (
            <Button onClick={() => begin.mutate()} disabled={begin.isPending}>
              {begin.isPending ? "Preparing…" : "Begin TOTP enrollment"}
            </Button>
          )}
          <hr />
          <h3>Remove TOTP</h3>
          <p className="fieldHint">
            Current password and a current authenticator code are required.
          </p>
          <label>
            Current password
            <input
              type="password"
              autoComplete="current-password"
              value={disablePassword}
              onChange={(event) => setDisablePassword(event.target.value)}
            />
          </label>
          <label>
            Authenticator code
            <input
              inputMode="numeric"
              autoComplete="one-time-code"
              value={disableCode}
              onChange={(event) => setDisableCode(event.target.value)}
            />
          </label>
          <Button
            className="secondary"
            disabled={disable.isPending || !disablePassword || !disableCode}
            onClick={() => disable.mutate()}
          >
            Disable TOTP
          </Button>
        </Card>
      </div>
      {owner ? (
        <Card>
          <div className="sectionTitle">
            <div>
              <p className="eyebrow">Owner administration</p>
              <h2>Dashboard users</h2>
            </div>
            <UserPlus aria-hidden="true" />
          </div>
          <form
            className="inlineForm"
            onSubmit={userForm.handleSubmit((values) => create.mutate(values))}
          >
            <label>
              Username
              <input {...userForm.register("username")} />
            </label>
            <label>
              Temporary password
              <input
                type="password"
                autoComplete="new-password"
                {...userForm.register("password")}
              />
            </label>
            <label>
              Role
              <select {...userForm.register("role")}>
                <option value="viewer">Viewer</option>
                <option value="operator">Operator</option>
                <option value="admin">Admin</option>
              </select>
            </label>
            <Button type="submit" disabled={create.isPending}>
              Create user
            </Button>
          </form>
          <ul className="itemList">
            {users.data?.users.map((user) => (
              <li key={user.username}>
                <span>
                  <strong>{user.username}</strong>
                  <small>
                    {user.role} · {user.totpEnabled ? "TOTP enabled" : "Password only"}
                  </small>
                </span>
                {user.role !== "owner" ? (
                  <Button className="danger compact" onClick={() => setRemove(user)}>
                    <Trash2 aria-hidden="true" />
                    Delete
                  </Button>
                ) : (
                  <span className="tag">Primary owner</span>
                )}
              </li>
            ))}
          </ul>
        </Card>
      ) : null}
      <ConfirmDialog
        open={Boolean(remove)}
        title="Delete dashboard user?"
        description={`${remove?.username ?? "This account"} will lose control-plane access and active sessions will be invalidated.`}
        confirmLabel="Delete user"
        destructive
        busy={drop.isPending}
        onClose={() => setRemove(null)}
        onConfirm={() => remove && drop.mutate(remove.username)}
      />
    </>
  );
}
