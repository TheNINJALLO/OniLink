import type { ButtonHTMLAttributes, PropsWithChildren, ReactNode } from "react";
import { AlertCircle, CheckCircle2, LoaderCircle } from "lucide-react";

export function Card({ children, className = "" }: PropsWithChildren<{ className?: string }>) {
  return <section className={`card ${className}`}>{children}</section>;
}

export function PageHeader({
  title,
  description,
  actions,
}: {
  title: string;
  description: string;
  actions?: ReactNode;
}) {
  return (
    <header className="pageHeader">
      <div>
        <p className="eyebrow">Control plane</p>
        <h1>{title}</h1>
        <p>{description}</p>
      </div>
      {actions ? <div className="headerActions">{actions}</div> : null}
    </header>
  );
}

export function Button({
  className = "",
  children,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement>) {
  return (
    <button className={`button ${className}`} {...props}>
      {children}
    </button>
  );
}

export function Status({
  state,
  children,
}: PropsWithChildren<{ state: "ok" | "warning" | "danger" | "neutral" }>) {
  return (
    <span className={`status status-${state}`}>
      <span aria-hidden="true" />
      {children}
    </span>
  );
}

export function Notice({ message, error = false }: { message: string; error?: boolean }) {
  if (!message) return null;
  return (
    <div
      className={`notice ${error ? "noticeError" : "noticeSuccess"}`}
      role={error ? "alert" : "status"}
    >
      {error ? <AlertCircle aria-hidden="true" /> : <CheckCircle2 aria-hidden="true" />}
      <span>{message}</span>
    </div>
  );
}

export function Loading({ label = "Loading" }: { label?: string }) {
  return (
    <div className="loading" role="status">
      <LoaderCircle aria-hidden="true" />
      {label}…
    </div>
  );
}

export function Empty({ title, detail }: { title: string; detail: string }) {
  return (
    <div className="empty">
      <strong>{title}</strong>
      <span>{detail}</span>
    </div>
  );
}

export function FieldError({ id, children }: PropsWithChildren<{ id: string }>) {
  return children ? (
    <p id={id} className="fieldError">
      {children}
    </p>
  ) : null;
}
