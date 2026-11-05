# redact-secret.awk — turn a grep hit into a redacted finding.
#
# WHY: a secret scanner must never write the secret into the log. The
#      redaction therefore happens here, in one place, and the shell script
#      only sees the finished line. Keeping the awk program in its own file
#      also keeps the quoting simple: the shell script does not have to embed
#      a program that contains quotes.
#
# USAGE
#   SECRET_PATTERN="<extended regular expression>" \
#     grep -nEI -- "$SECRET_PATTERN" <files> \
#     | awk -f redact-secret.awk
#
#   The input is one grep hit per line: "<file>:<line>:<content>".
#   The output is one finding per line:
#     "<file>:<line> — <description> (rule <id>) — redacted: <preview>"
#
#   The preview keeps the first characters of the match — enough to recognise
#   the kind of secret — and replaces the rest with a marker. For an
#   assignment the part behind the first quote is replaced as well, so the
#   value never reaches the log.
#
# EXIT CODES
#   0  the hits were processed
#   1  the input could not be read
#

BEGIN {
  DQ = sprintf("%c", 34)
  SQ = sprintf("%c", 39)
  ID = ENVIRON["SECRET_ID"]
  DESC = ENVIRON["SECRET_DESC"]
  PATTERN = ENVIRON["SECRET_PATTERN"]
  if (PATTERN == "") {
    print "ERROR: SECRET_PATTERN is not set" > "/dev/stderr"
    exit 1
  }
}

# The value of an assignment: the text between the first and the last quote
# of the match.
function value_of(m,   q1, q2, i, c) {
  q1 = index(m, DQ)
  if (q1 == 0) q1 = index(m, SQ)
  if (q1 == 0) return ""
  q2 = 0
  for (i = length(m); i > q1; i--) {
    c = substr(m, i, 1)
    if (c == DQ || c == SQ) { q2 = i; break }
  }
  if (q2 <= q1) return ""
  return substr(m, q1 + 1, q2 - q1 - 1)
}

# A value that only names a variable is not a secret: the secret is still in
# the environment, where it belongs.
function is_variable_reference(v) {
  if (v == "") return 0
  if (v ~ /^\$[A-Za-z_][A-Za-z0-9_]*$/) return 1
  if (v ~ /^\$\{[^}]*\}$/) return 1
  return 0
}

{
  first = index($0, ":")
  if (first == 0) next
  file = substr($0, 1, first - 1)
  rest = substr($0, first + 1)
  second = index(rest, ":")
  if (second == 0) next
  line = substr(rest, 1, second - 1)
  content = substr(rest, second + 1)

  # A line that only documents a credential is not a credential.
  lowered = tolower(content)
  if (lowered ~ /getenv|%s|<your|example|placeholder|redacted|dummy/) next

  out = ""
  suppress = 0
  s = content
  while (match(s, PATTERN) > 0) {
    m = substr(s, RSTART, RLENGTH)
    if (is_variable_reference(value_of(m))) {
      suppress = 1
      break
    }
    quote = index(m, DQ)
    if (quote == 0) quote = index(m, SQ)
    if (quote > 0) {
      out = out substr(s, 1, RSTART - 1) substr(m, 1, quote) \
            "...[REDACTED " length(substr(m, quote + 1)) " chars]"
    } else {
      out = out substr(s, 1, RSTART - 1) substr(m, 1, 4) \
            "...[REDACTED " length(m) " chars]"
    }
    s = substr(s, RSTART + RLENGTH)
  }
  if (suppress) next

  printf "ERROR: %s:%s — %s (rule %s) — redacted: %s\n", \
    file, line, DESC, ID, out s
}
