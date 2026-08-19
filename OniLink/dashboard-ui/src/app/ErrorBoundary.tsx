import { Component, type ReactNode } from "react";

export class ErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  override state = { failed: false };
  static getDerivedStateFromError() {
    return { failed: true };
  }
  override componentDidCatch() {
    /* Intentionally avoid logging potentially sensitive render data. */
  }
  override render() {
    if (this.state.failed)
      return (
        <main className="fatal" role="alert">
          <h1>Control plane unavailable</h1>
          <p>The interface encountered an unexpected error. No action was submitted.</p>
          <button className="button" onClick={() => window.location.reload()}>
            Reload safely
          </button>
        </main>
      );
    return this.props.children;
  }
}
