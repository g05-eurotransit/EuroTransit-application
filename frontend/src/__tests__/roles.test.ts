import { describe, expect, it } from "vitest";
import { realmRoles } from "../auth/roles";

/** The claim shapes the local Keycloak actually issues. */
describe("realm roles", () => {
  it("reads the roles of an operations account", () => {
    expect(realmRoles({ preferred_username: "admin", realm_access: { roles: ["operations"] } })).toEqual([
      "operations",
    ]);
  });

  it("reads the roles of a customer account", () => {
    expect(
      realmRoles({ preferred_username: "alice", realm_access: { roles: ["default-roles-eurotransit", "customer"] } }),
    ).toEqual(["default-roles-eurotransit", "customer"]);
  });

  it("is empty for a token without the claim, rather than throwing", () => {
    expect(realmRoles({ preferred_username: "admin" })).toEqual([]);
    expect(realmRoles(null)).toEqual([]);
    expect(realmRoles(undefined)).toEqual([]);
  });

  it("ignores a claim of the wrong shape", () => {
    expect(realmRoles({ realm_access: "operations" })).toEqual([]);
    expect(realmRoles({ realm_access: { roles: "operations" } })).toEqual([]);
    expect(realmRoles({ realm_access: { roles: [1, "operations", null] } })).toEqual(["operations"]);
  });
});
