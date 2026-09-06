import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import * as api from "../../api/client";
import { UpdateCenterPage } from "./UpdateCenterPage";

function renderCenter() {
  const request = vi.spyOn(api, "request").mockResolvedValue({
    updates: {},
    protocols: {},
    packs: {},
    players: {},
    cluster: {},
    metrics: {},
    restoreError: "",
    continuityEnabled: true,
    playerServicesEnabled: true,
  });
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={client}>
      <UpdateCenterPage />
    </QueryClientProvider>,
  );
  return request;
}

describe("Update Center", () => {
  it("uploads the selected file as a binary body in the selected scope", async () => {
    const request = renderCenter();
    const user = userEvent.setup();
    await user.clear(screen.getByLabelText("Tenant"));
    await user.type(screen.getByLabelText("Tenant"), "acme");
    await user.selectOptions(screen.getByLabelText("Operating system"), "linux");
    const file = new File(["fixture archive bytes"], "bedrock.zip", { type: "application/zip" });
    const fileInput = screen.getByLabelText<HTMLInputElement>("Archive file");
    await user.upload(fileInput, file);
    expect(fileInput.files?.[0]).toBe(file);
    const upload = screen.getByRole("button", { name: "Upload and verify" });
    expect(upload).toBeEnabled();
    // JSDOM's required-file validation does not see user-event's simulated FileList.
    fireEvent.submit(upload.closest("form")!);
    await waitFor(() =>
      expect(request).toHaveBeenCalledWith(
        "/api/update-center/upload?tenant=acme&proxy=main&kind=server&version=1.26.45.1&platform=linux",
        { method: "POST", rawBody: file },
      ),
    );
    expect(screen.getByText("Native runtime: Endstone")).toBeVisible();
  });

  it("requires a matching review and confirmation, then invalidates them when the artifact changes", async () => {
    const request = renderCenter();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText("Server artifact SHA-256"), "candidate-one");
    await user.type(screen.getByLabelText("Backend name"), "survival");
    await user.selectOptions(screen.getByLabelText("Action"), "update.deploy");
    expect(screen.getByRole("button", { name: "Deploy reviewed candidate" })).toBeDisabled();
    await user.selectOptions(screen.getByLabelText("Action"), "update.preview");
    request.mockResolvedValueOnce({
      ready: true,
      backend: "survival",
      artifact: { id: "candidate-one" },
      blockers: [],
    });
    await user.click(screen.getByRole("button", { name: "Review candidate" }));
    await screen.findByRole("heading", { name: "Latest result" });
    await user.selectOptions(screen.getByLabelText("Action"), "update.deploy");
    const deploy = screen.getByRole("button", { name: "Deploy reviewed candidate" });
    expect(deploy).toBeDisabled();
    await user.click(screen.getByRole("checkbox", { name: /I reviewed this exact candidate/ }));
    expect(deploy).toBeEnabled();
    await user.type(screen.getByLabelText("Server artifact SHA-256"), "-changed");
    expect(deploy).toBeDisabled();
    expect(
      screen.getByRole("checkbox", { name: /I reviewed this exact candidate/ }),
    ).not.toBeChecked();
    expect(
      request.mock.calls.some(([, options]) => options?.body?.operation === "update.deploy"),
    ).toBe(false);
  });
});
