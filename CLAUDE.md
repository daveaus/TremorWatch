# CLAUDE.md — TremorWatch Operating System

> **CRITICAL SAFETY WARNING: NEVER uninstall apps without user confirmation.**
> The phone app stores local Room database data. Uninstalling destroys this data.
> 1. Warn user. 2. Suggest export. 3. Get explicit confirmation.
>
> **WORK PROFILE WARNING:** Ensure that when installing apps on the phone you install it in the normal profile NOT the work profile.

Identity & Operating Context
You are working with the Lead Developer for **TremorWatch**, a Wear OS + Android app for monitoring and tracking tremor severity.

**Project Structure:**
- `app/` - Wear OS watch app
- `phone/` - Android phone companion
- `shared/` - Shared Kotlin code

Build & Deploy
JAVA_HOME is NOT set in the system environment. You MUST export it before every Gradle command:
```
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
```

Build commands (always prefix with the export above):
- Watch app: `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && C:/Users/david/AndroidStudioProjects/TremorWatch/gradlew.bat :app:assembleDebug --no-daemon 2>&1`
- Phone app: `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && C:/Users/david/AndroidStudioProjects/TremorWatch/gradlew.bat :phone:assembleDebug --no-daemon 2>&1`

APK locations:
- Watch: `app/build/outputs/apk/debug/app-debug.apk`
- Phone: `phone/build/outputs/apk/debug/phone-debug.apk`

ADB devices:
- Watch (Samsung SM-L705F): `10.184.101.73:36317`
- Phone (Pixel 10 Pro XL): `58151FDCQ006K7`

Deploy to watch: `adb -s 10.184.101.73:36317 install -r app/build/outputs/apk/debug/app-debug.apk`
Deploy to phone: `adb -s 58151FDCQ006K7 install -r phone/build/outputs/apk/debug/phone-debug.apk`

**Important:** Do NOT use `./gradlew.bat` or `.\gradlew.bat` — use the full path or just `gradlew.bat` from the project directory. The `./` prefix fails in the bash shell on Windows.

Git Rules
- Only commit and push actual project source files (`app/`, `phone/`, `shared/`, `CLAUDE.md`, build configs).
- Never commit logs, notes, investigation reports, CSVs, screenshots, or other non-project files.
- Never add `Co-Authored-By` or any AI attribution to commit messages.
- Keep commit messages concise and descriptive of the change.

Communication Rules
Direct, professional dialogue. No filler, no unnecessary adjectives.
Active voice. Address as "you" and "your."
Lead with outcomes and impact, not process descriptions.
State confidence levels (high/medium/low) on recommendations.
Bold key metrics, decisions, and action items.
Use bullet points only for lists, procedures, comparisons — prose for analysis and strategy.
Brief acknowledgments only when they add clarity.

Session Startup Protocol
Execute this sequence at the start of EVERY session:

Step 1: Orientation (Always)
Read this file completely. Then check if `./docs/summaries/` exists.
**MANDATORY:** Check connected devices and identify which ADB device is being used for this session (Watch or Phone).

If `./docs/summaries/` exists: Read all files in it. These are compressed state from previous sessions. This is your primary context — do NOT read source documents unless specifically needed for the current task.

Step 2: Context Budget Check (Always)
Before starting any work, mentally assess your context budget:
Your total context window is ~200K tokens
This `CLAUDE.md` consumes ~3,500 tokens
Summaries from previous sessions: variable
Your working budget is what remains after the above
Plan your approach to stay within 60-70% total capacity
If the task requires reading many files, use the Document Processing Protocol below

Step 3: State Your Understanding (Always)
Before doing any work, tell the user:
What you understand the current project state to be
What phase you believe we're in
What you plan to do in this session
Any questions or ambiguities
Do NOT start work without completing Steps 1-3.

Context Management Rules
These rules override all other instincts. Context management is your highest priority.

Rule 1: Never Bulk-Read Documents
When the user provides multiple documents or points you to a directory of files:
NEVER read all files into your context at once
ALWAYS use the Document Processing Protocol (below)
Process documents one at a time through a subagent or sequential read-summarize-clear cycle

Rule 2: Write State to Disk, Not Conversation
After completing any meaningful unit of work:
Write a summary to `./docs/summaries/` using the appropriate template from `templates/claude-templates.md`
Include: decisions made, files created/modified, key data points, open items
This file becomes the starting context for the next session

Rule 3: Manual Compaction at Logical Breakpoints
Run `/compact` proactively at 60-70% context usage — do not wait for auto-compact
When compacting, specify what to preserve: `/compact keep: project context, current task state, file paths, key decisions`
BEFORE compacting, ALWAYS write current state to a summary file first — compaction will lose detail you cannot recover

Rule 4: One Concern Per Session
Structure work into focused sessions:
Session = one phase of work (research OR writing OR review — not all three)
When switching phases, write a handoff summary and suggest starting a new session
Tell the user: "We should start a fresh session for [next phase]. I've written the handoff to ./docs/summaries/[file]."

Rule 5: CLAUDE.md Is an Index, Not an Encyclopedia
This file points to where information lives — it does not contain the information itself
Never suggest expanding this file with project-specific content
Project-specific context goes in `./docs/summaries/`

Rule 6: Monitor Context Continuously
After every 3-4 exchanges, mentally estimate context usage
If approaching 60%, proactively tell the user and suggest compaction or session split
Use `/context` when available to check actual token usage

Session Discipline
When to Split Sessions
Split to a new session when ANY of these are true:
Context usage exceeds 60%
You're switching from one phase of work to another (research → writing → review)
The conversation has exceeded ~20 substantive exchanges
You're about to start a task that requires reading 3+ large files

How to Split Cleanly
Write a handoff file to `./docs/summaries/handoff-[date]-[topic].md` using Template 4 from `templates/claude-templates.md`
Tell the user: "We should start a fresh session. I've written the handoff to [path]."
The next session picks up by reading the handoff file in Step 1 of the Startup Protocol

What Makes Handoffs Work
The handoff template preserves exactly what gets lost in conversation continuation:
Exact file paths for every output created
Decisions with rationale (not just what, but why)
Precise numbers that compaction would round or drop
Open questions explicitly marked, not silently resolved
Next steps as ordered instructions for the next session

Cost of NOT Splitting
Every message in a long conversation carries the full conversation history. By message 30, each exchange costs ~50K+ tokens of context. A fresh session with handoff summaries starts at ~5K tokens — 10x cheaper per message.

Structured Summarization: Why It Matters
Claude's default summarization loses five categories of information:
Precise numbers get rounded or dropped
Conditional logic (IF/BUT/EXCEPT) collapses to simple statements
Decision rationale (WHY) evaporates — only WHAT survives
Cross-document relationships flatten to single statements
Open questions get silently resolved as settled
The templates in `templates/claude-templates.md` fix this with explicit fields for exact numbers, conditional logic, decision rationale, cross-references, and uncertainty markers.

CRITICAL: When filling templates, use the structured fields — NOT natural prose. Prose triggers the exact compression behaviors the templates prevent.

For all summary, handoff, decision, analysis, and project brief templates: Read `templates/claude-templates.md`.

Document Processing Protocol
Use this whenever you need to process multiple documents or large files.

For 1-3 Short Documents (< 2K words each)
Read sequentially. After EACH document, write a Source Document Summary (Template 1 from `templates/claude-templates.md`) to disk. Then proceed with work using summaries only.

For 4+ Documents OR Any Document > 2K Words
Step 1: List all documents with file sizes. Present to user for prioritization.
Step 2: Process each document individually:
Read one document
Extract into Source Document Summary format
Write to `./docs/summaries/source-[filename].md`
Release the document from active consideration before reading the next
Step 3: After all documents are processed, read only the summaries to form your working context.
Step 4: Cross-reference summaries for contradictions or dependencies. Note these explicitly.
Step 5: Proceed with the actual task using summaries as your reference.

Archive Protocol
Raw File Archival
After creating a Source Document Summary for any raw file:
Move the raw file to `docs/archive/`
Record the move in the source summary's header: Archived From: [original path]
NEVER read from `docs/archive/` unless the user explicitly says "go back to the original [filename]"
This prevents accidental re-processing and keeps working directories clean.

Summary Lifecycle Rules
Session handoffs expire: After a new handoff is written, the PREVIOUS handoff moves to `docs/archive/handoffs/`. Only the LATEST handoff stays in `docs/summaries/`.
Decision records persist: Decision records (DR-*) stay in `docs/summaries/` permanently — they are institutional memory.
Source summaries persist: Source document summaries stay in `docs/summaries/` until the project ends — they replace raw documents.
Analysis summaries: Keep only the latest version. If re-run, the new one replaces the old (archive the old one).
Maximum active summaries: If `docs/summaries/` exceeds 15 files, consolidate older source summaries into a single `project-digest.md` and archive the originals.

Subagent Deployment Rules
Situation | Approach | Why
--- | --- | ---
Reading/analyzing documents | Subagent | Keeps source content out of main context
Research and competitive analysis | Subagent | Heavy reading, return summary only
Writing deliverables | Main agent | Needs full decision-making context
Schema/architecture design | Main agent | Needs holistic project understanding
Code generation | Subagent | Isolated implementation, return result
Review and QA | Subagent | Fresh perspective, no bias from writing
Subagent output must conform to the Output Contracts in `templates/claude-templates.md`. No free-form prose returns.

Quality Gates
Before delivering any output to the user, verify:
- Does this output match what was actually requested? (not what you assumed)
- Are all claims backed by specific data or rationale?
- Is the language direct and active voice? (no "it should be noted that...")
- For proposals/client materials: Is ROI or business impact quantified with EXACT numbers?
- For schemas/prompts: Are all fields defined and all edge cases addressed?
- Have you written a summary file for this session's work?
- Are all open questions explicitly marked as OPEN/ASSUMED, not silently resolved?
- Do any decisions reference their rationale and rejected alternatives?

Error Recovery
If Context Gets Corrupted
Write current understanding to `./docs/summaries/recovery-[date].md`
Tell the user: "My context has degraded. I've saved what I have. Recommend starting a fresh session."
`/clear` or start new session

If Auto-Compact Fires Unexpectedly
Re-read `./docs/summaries/` to rebuild context
Re-read this `CLAUDE.md` to restore operating instructions
Tell the user what you think you may have lost

If Task Will Exceed Context
Tell the user upfront: "This task involves processing [X files / Y tokens]. I recommend: [phased approach with session boundaries]." Never silently attempt more than you can handle.

Quick Reference
Command | When to Use
--- | ---
`/compact` | At 60-70% context. ALWAYS write handoff first.
`/compact keep: [specifics]` | Preserve particular details through compaction
`/clear` | Starting a genuinely new task
`/context` | Check token usage — do this frequently
`claude --resume` | Continue a previous session
`claude --continue` | Resume the most recent session
`Shift+Tab` (×2) | Enter Plan Mode before complex work
`Esc` | Stop Claude mid-action

File Organization Standard
project-root/
├── CLAUDE.md                          ← This file (core instructions only)
├── templates/
│   └── claude-templates.md            ← Summary, handoff, decision, output contract templates
├── app/                               ← Watch App Source
├── phone/                             ← Phone App Source
├── shared/                            ← Shared Kotlin Source
├── docs/
│   ├── discovery/                     ← Raw client inputs, briefs, requirements
│   ├── research/                      ← Market research, competitive analysis sources
│   ├── requirements/                  ← Structured requirements (from discovery)
│   ├── strategy/                      ← Positioning, value props, go-to-market
│   ├── archive/                       ← Processed raw files (DO NOT read unless told)
│   │   └── handoffs/                  ← Superseded session handoffs
│   └── summaries/                     ← ALL active session state lives here
│       ├── 00-project-brief.md        ← Initial project setup
│       ├── source-[filename].md       ← Per-document summaries
│       ├── analysis-[topic].md        ← Research outputs
│       ├── decision-[num]-[topic].md  ← Decision records
│       └── handoff-[date]-[topic].md  ← Latest session handoff ONLY
├── output/
│   ├── schemas/                       ← Data models, agent definitions
│   ├── prompts/                       ← System prompts for agents
│   ├── deliverables/                  ← Client-facing final outputs
│   └── presentations/                 ← Decks, workshop materials
└── .claude/
    ├── agents/                        ← Custom subagent definitions
    └── commands/                      ← Custom slash commands

End of CLAUDE.md
This file is your operating system. Follow it precisely. For all structured templates (summaries, handoffs, decisions, output contracts, workflow phases), read `templates/claude-templates.md` on demand — do not memorize them.