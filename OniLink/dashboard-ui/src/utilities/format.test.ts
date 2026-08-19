import { endpoint } from "./format";

describe("endpoint", () => {
  it("joins an IPv4 address or DNS name with its port", () => {
    expect(endpoint("45.143.196.108", "19135")).toBe("45.143.196.108:19135");
    expect(endpoint("play.example.com", 19135)).toBe("play.example.com:19135");
  });

  it("wraps a bare IPv6 address and leaves a bracketed address intact", () => {
    expect(endpoint("2001:db8::10", "19135")).toBe("[2001:db8::10]:19135");
    expect(endpoint("[2001:db8::10]", "19135")).toBe("[2001:db8::10]:19135");
  });

  it("does not show a misleading partial endpoint", () => {
    expect(endpoint("", "19135")).toBe("Not entered yet");
    expect(endpoint("45.143.196.108", "")).toBe("Not entered yet");
  });
});
