import { connection } from "next/server";
import { getSystem } from "@/lib/server-api";
import { ModeBadges } from "./ModeBadges";

export async function ModeBadgesServer() {
  await connection();
  return <ModeBadges info={await getSystem()} />;
}
