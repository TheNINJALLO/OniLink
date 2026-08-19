import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./app/App";
import { ErrorBoundary } from "./app/ErrorBoundary";
import { AuthProvider } from "./auth/AuthProvider";
import "./styles/tokens.css";
import "./styles/layout.css";
import "./styles/components.css";
import "./styles/responsive.css";

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 2_000,
      retry: (count, error) => !(error instanceof DOMException) && count < 1,
      refetchOnWindowFocus: true,
    },
    mutations: { retry: false },
  },
});
const root = document.getElementById("root");
if (!root) throw new Error("Dashboard root element is missing");
createRoot(root).render(
  <StrictMode>
    <ErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <App />
        </AuthProvider>
      </QueryClientProvider>
    </ErrorBoundary>
  </StrictMode>,
);
