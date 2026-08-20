// SPDX-License-Identifier: MulanPSL-2.0
// `rbnx call` — invoke one capability directly, bypassing the Pilot/LLM.
//
// Builds a single-node RTDL Plan (one `do` leaf whose `CapabilityCall`
// targets `contract_id`) and submits it straight to the Executor's
// `Execute` RPC, then streams the resulting `RtdlEvent`s to stdout.
// It is the deterministic, scriptable sibling of `rbnx ask` / `rbnx chat`:
// no model in the loop, so it is what you want for hardware bring-up and
// one-shot control ("takeoff", "land", "gimbal_rotate", …).
//
// Usage:
//   rbnx call <contract_id> [--provider <id>] [--args '<json>'] [--server <ep>]
//
// Examples:
//   rbnx call robonix/primitive/drone/takeoff --args '{"altitude": 3.0}'
//   rbnx call robonix/primitive/drone/land --provider drone_bridge

use anyhow::{Context, Result};
use robonix_atlas::client::{self as atlas_client, AtlasClient};
use robonix_atlas::pb as atlas_pb;
use std::io::{self, Write};
use tokio_stream::StreamExt;
use tonic::Request;
use uuid::Uuid;

use crate::pb::contracts::robonix_system_executor_execute_client::RobonixSystemExecutorExecuteClient;
use crate::pb::executor::rtdl_event::RtdlEventEnum;
use crate::pb::pilot::rtdl_node_state::RtdlNodeStateEnum;
use crate::pb::pilot::{CapabilityCall, Plan, RtdlNode};

const CONSUMER_ID: &str = "rbnx-cli/call";
const EXECUTOR_CONTRACT: &str = "robonix/system/executor/execute";
// RtdlNode.node_kind — mirror pilot/msg/RtdlNode.msg (do = leaf).
const RTDL_DO: u32 = 2;

pub async fn execute(
    server: &str,
    contract_id: &str,
    provider: Option<&str>,
    args: &str,
    json: bool,
) -> Result<()> {
    let mut atlas = AtlasClient::connect(server)
        .await
        .with_context(|| format!("connect to atlas at '{server}'"))?;

    // Resolve the provider that actually runs the capability: an explicit
    // `--provider` wins; otherwise pick the first provider offering it.
    let provider_id = match provider {
        Some(p) => p.to_string(),
        None => resolve_provider(&mut atlas, contract_id).await?,
    };

    // Route the call through the Executor (the real runtime dispatch path),
    // not a direct provider connection, so the request flows through the same
    // Plan/RTDL machinery the Pilot uses.
    let (channel_id, _exec_provider, channel) = atlas_client::connect_to_capability(
        &mut atlas,
        CONSUMER_ID,
        EXECUTOR_CONTRACT,
    )
    .await
    .context("locate executor via atlas")?;
    let mut client = RobonixSystemExecutorExecuteClient::new(channel);

    // Single-leaf plan: one `do` node whose call is the requested capability.
    let plan = Plan {
        plan_id: Uuid::new_v4().to_string(),
        session_id: Uuid::new_v4().to_string(),
        round: 0,
        nodes: vec![RtdlNode {
            node_kind: RTDL_DO,
            children: vec![],
            call: Some(CapabilityCall {
                call_id: Uuid::new_v4().to_string(),
                provider_id: provider_id.clone(),
                contract_id: contract_id.to_string(),
                args_json: args.to_string(),
            }),
            op_id: format!("call-{}", leaf_name(contract_id)),
            description: format!("{contract_id} (rbnx call)"),
        }],
        root_index: 0,
    };

    if !json {
        eprintln!("[call] {provider_id} :: {contract_id} args={args}");
    }

    let mut stream = client
        .execute(Request::new(plan))
        .await
        .context("executor Execute RPC failed")?
        .into_inner();

    let stdout = io::stdout();
    let mut out = stdout.lock();
    let mut failed = false;

    while let Some(event) = stream.next().await {
        let event = event.context("executor stream error")?;
        match event.event_kind {
            k if k == RtdlEventEnum::PlanStarted as u32 => {
                if !json {
                    if let Some(ps) = &event.plan_started {
                        writeln!(out, "[plan] {} started", ps.plan_id)?;
                    }
                }
            }
            k if k == RtdlEventEnum::NodeState as u32 => {
                if let Some(ns) = &event.node_state {
                    let ok = ns.state == RtdlNodeStateEnum::Succeeded as u32;
                    if !ok {
                        failed = true;
                    }
                    if let Some(lr) = &ns.leaf_result {
                        if json {
                            let v = serde_json::json!({
                                "op_id": ns.op_id,
                                "contract_id": lr.contract_id,
                                "provider_id": lr.provider_id,
                                "success": lr.success,
                                "output": lr.output,
                                "error": lr.error,
                            });
                            writeln!(out, "{v}")?;
                        } else {
                            let mark = if ok { "✓" } else { "✗" };
                            let body = if lr.success {
                                lr.output.clone()
                            } else {
                                lr.error.clone()
                            };
                            writeln!(
                                out,
                                "  [{mark} {}] {}",
                                leaf_name(&lr.contract_id),
                                compact_one_line(&body, 240)
                            )?;
                        }
                    }
                }
            }
            k if k == RtdlEventEnum::PlanComplete as u32 => {
                if let Some(pc) = &event.plan_complete {
                    if pc.any_failed {
                        failed = true;
                    }
                    if !json {
                        let mark = if pc.any_failed { "✗" } else { "✓" };
                        writeln!(out, "[plan] {} complete {mark}", pc.plan_id)?;
                    }
                }
            }
            _ => {}
        }
        out.flush()?;
    }

    let _ = atlas.disconnect_capability(&channel_id).await;
    if failed {
        anyhow::bail!("capability call failed");
    }
    Ok(())
}

/// Find the first provider offering `contract_id` over MCP.
async fn resolve_provider(atlas: &mut AtlasClient, contract_id: &str) -> Result<String> {
    let rows = atlas
        .flatten_capabilities(contract_id, "", atlas_pb::Transport::Mcp)
        .await
        .with_context(|| format!("query atlas for '{contract_id}'"))?;
    let row = rows.into_iter().next().with_context(|| {
        format!(
            "no provider offers '{contract_id}' over MCP — is the primitive built, \
             booted and registered? (check `rbnx caps`)"
        )
    })?;
    Ok(row.provider_id)
}

/// Last path segment of a contract id: `robonix/primitive/drone/takeoff` → `takeoff`.
fn leaf_name(contract_id: &str) -> &str {
    contract_id
        .rsplit_once('/')
        .map(|(_, l)| l)
        .unwrap_or(contract_id)
}

fn compact_one_line(s: &str, n: usize) -> String {
    let flat: String = s
        .chars()
        .map(|c| if c == '\n' || c == '\r' { ' ' } else { c })
        .collect();
    if flat.chars().count() > n {
        let mut out: String = flat.chars().take(n).collect();
        out.push('…');
        out
    } else {
        flat
    }
}
