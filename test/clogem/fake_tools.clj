;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.fake-tools
  "Stand-ins for the Pagefind binary and its release tarball, so `bb test`
  never touches the network (D-P3-8). Not a test namespace itself."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn fake-pagefind!
  "Write an executable `pagefind_extended` stand-in into `dir` and return its
  path. It records its arguments in `args.txt` beside itself, writes the
  bundle files the theme links to plus a `pagefind-entry.json` under
  `<--site>/<--output-subdir>/`, prints `output`, and exits `exit`. Without
  `--site` it writes no bundle and exits 2: an empty site would otherwise
  mean `/pagefind`, at the filesystem root."
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
                "if [ -z \"$site\" ]; then echo 'fake pagefind: --site is required' >&2; exit 2; fi"
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
  assets do. Returns its path. `opts` go to `fake-pagefind!` — a distinct
  `:output` makes a distinct binary."
  [dir & opts]
  (let [stage (fs/path dir "stage")]
    (apply fake-pagefind! stage opts)
    (fs/delete-if-exists (fs/path stage "args.txt"))
    (p/shell {:dir (str stage)} "tar" "-czf" (str (fs/path dir "fake.tar.gz")) "pagefind_extended")
    (str (fs/path dir "fake.tar.gz"))))

(defn symlink-tarball!
  "A `.tar.gz` whose `pagefind_extended` is a symbolic link to `target`."
  [dir target]
  (let [stage (fs/path dir "stage-link")]
    (fs/create-dirs stage)
    (fs/create-sym-link (fs/path stage "pagefind_extended") target)
    (p/shell {:dir (str stage)} "tar" "-czf" (str (fs/path dir "link.tar.gz")) "pagefind_extended")
    (str (fs/path dir "link.tar.gz"))))

(defn read-fragments
  "Every Pagefind fragment under `<out>/pagefind/fragment/`, parsed: gzip,
  then a `pagefind_dcd` signature, then the JSON of one indexed page —
  {:url :content :filters :meta …}, with `:lang` from the file name. The
  content is exactly the text Pagefind indexed, so a word glued to its
  neighbour shows up here as it does in search."
  [out]
  (for [f (sort (map str (fs/glob (fs/path out "pagefind" "fragment") "*.pf_fragment")))]
    (with-open [in (java.util.zip.GZIPInputStream. (io/input-stream (fs/file f)))]
      (let [s (slurp in :encoding "UTF-8")]
        (assoc (json/parse-string (subs s (str/index-of s "{")) true)
               :lang (first (str/split (str (fs/file-name f)) #"_")))))))

(defn- fake-binary!
  "An executable shell script at `f` that prints `output`."
  [f output]
  (fs/create-dirs (fs/parent f))
  (spit (fs/file f) (str "#!/bin/sh\necho '" output "'\n"))
  (fs/set-posix-file-permissions f "rwxr-xr-x")
  f)

(defn fake-tool-archive!
  "A release archive for the `clogem.tools` descriptor `tool` holding a fake
  binary named for `platform` that prints `output`, laid out as upstream
  ships it: Pagefind's and Chroma's are .tar.gz (Chroma's root also holds
  COPYING and README.md), the fswatcher pod's a zip whose single member is
  the binary. `:extra-zip-entries` adds {name → content} entries to a zip.
  Returns the archive's path."
  [dir tool platform & {:keys [output extra-zip-entries] :or {output "fake tool ran"}}]
  (let [member ((:member tool) platform)
        stage  (fs/path dir (str "stage-" (name (:id tool))))]
    (fs/create-dirs stage)
    (fake-binary! (fs/path stage member) output)
    (case (:archive tool)
      :zip
      (let [zip (fs/path dir (str (name (:id tool)) ".zip"))]
        (with-open [zout (java.util.zip.ZipOutputStream. (io/output-stream (fs/file zip)))]
          (doseq [[n content] extra-zip-entries]
            (.putNextEntry zout (java.util.zip.ZipEntry. ^String n))
            (.write zout (.getBytes (str content) "UTF-8"))
            (.closeEntry zout))
          (.putNextEntry zout (java.util.zip.ZipEntry. ^String member))
          (.write zout (fs/read-all-bytes (fs/path stage member)))
          (.closeEntry zout))
        (str zip))
      (let [tgz   (fs/path dir (str (name (:id tool)) ".tar.gz"))
            extra (when (= :chroma (:id tool))
                    (spit (fs/file stage "COPYING") "MIT\n")
                    (spit (fs/file stage "README.md") "chroma\n")
                    ["COPYING" "README.md"])]
        (apply p/shell {:dir (str stage)} "tar" "-czf" (str tgz) (concat extra [member]))
        (str tgz)))))
