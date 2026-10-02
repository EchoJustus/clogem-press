;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.fake-tools
  "Stand-ins for the Pagefind binary and its release tarball, so `bb test`
  never touches the network (D-P3-8). Not a test namespace itself."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(defn fake-pagefind!
  "Write an executable `pagefind_extended` stand-in into `dir` and return its
  path. It records its arguments in `args.txt` beside itself, writes the
  bundle files the theme links to plus a `pagefind-entry.json` under
  `<--site>/<--output-subdir>/`, prints `output`, and exits `exit`."
  [dir & {:keys [exit output] :or {exit 0 output "fake pagefind ran"}}]
  (let [f (fs/path dir "pagefind_extended")]
    (fs/create-dirs dir)
    (spit (fs/file f)
          (str/join
           "\n"
           ["#!/bin/sh"
            "printf '%s\\n' \"$@\" > \"$(dirname \"$0\")/args.txt\""
            (str "echo '" output "'")
            (if (zero? exit)
              (str/join
               "\n"
               ["site=''; sub=pagefind"
                "while [ $# -gt 0 ]; do"
                "  case \"$1\" in --site) site=\"$2\"; shift;; --output-subdir) sub=\"$2\"; shift;; esac"
                "  shift"
                "done"
                "mkdir -p \"$site/$sub\""
                ": > \"$site/$sub/pagefind-component-ui.js\""
                ": > \"$site/$sub/pagefind-component-ui.css\""
                "echo '{\"version\":\"fake\",\"languages\":{\"en\":{\"page_count\":2},\"ta\":{\"page_count\":1}}}' > \"$site/$sub/pagefind-entry.json\""
                "exit 0"])
              (str "echo 'boom' >&2\nexit " exit))
            ""]))
    (fs/set-posix-file-permissions f "rwxr-xr-x")
    (str f)))

(defn args-of
  "The arguments the fake binary at `bin` was last run with, or nil."
  [bin]
  (let [f (fs/path (fs/parent bin) "args.txt")]
    (when (fs/exists? f) (str/split-lines (slurp (fs/file f))))))

(defn fake-tarball!
  "A `.tar.gz` holding a fake `pagefind_extended` at its root, as the release
  assets do. Returns its path."
  [dir]
  (let [stage (fs/path dir "stage")]
    (fake-pagefind! stage)
    (fs/delete-if-exists (fs/path stage "args.txt"))
    (p/shell {:dir (str stage)} "tar" "-czf" (str (fs/path dir "fake.tar.gz")) "pagefind_extended")
    (str (fs/path dir "fake.tar.gz"))))
