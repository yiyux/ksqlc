# Agent notes

## XERJ (code search)

This project uses XERJ for reference coding. Before writing code that touches
unfamiliar territory: `xerj def "<symbol>"` for go-to-definition, or
`xerj search "<plain words>"` for ranked, definition-first passages.
Index a repo with `xerj autoindex <folder>`. Cite file:line for anything you rely on.

## Memory (XERJ)
- At the start of a task, call xerj_memory_recall in namespace "ksqlc" with the task description.
- When you learn a durable fact (architecture decision, convention, gotcha, user preference),
  store it with xerj_memory_store in namespace "ksqlc", with metadata {"kind": "decision|convention|gotcha|preference"}.
- If a stored fact becomes false, forget it rather than storing a contradiction.
