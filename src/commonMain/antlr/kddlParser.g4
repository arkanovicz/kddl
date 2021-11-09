parser grammar kddlParser;

options { tokenVocab = kddlLexer; }

database: DATABASE name=LABEL LC ( schema | link ) * RC EOF ;
schema: SCHEMA name=LABEL LC ( table | link )* RC ;
table: TABLE name=LABEL ( FS par=qualified ( LP direction RP )? )? LC field* RC ;
direction: ( UP | DOWN | LEFT | RIGHT ) ;
link: left=qualified ( left_mult=ST | left_single=LA )? LNK+ (  right_mult=ST | right_single=RA )? right=qualified ;
field: ( pk=ST | unique=EM )? name=LABEL (type ( optional=QM )? default? | ( optional=QM )? ARROW reference=qualified CASCADE? direction? ) ;
type: BOOLEAN
    | INT
    | SERIAL
    | LONG
    | FLOAT
    | DOUBLE
    | MONEY
    | NUMERIC LP prec=INTEGER ( CM scale=INTEGER )? RP
    | TIME
    | DATE
    | DATETZ
    | DATETIME
    | DATETIMETZ
    | INTERVAL
    | CHAR ( LP width=INTEGER RP )?
    | VARCHAR LP width=INTEGER RP
    | TEXT
    | ENUM LP value=STRING ( CM? value=STRING )* RP
    | JSON
    | JSONB ;
default: EQ expression ;
expression: NULL | boolean | number | STRING | function ;
boolean: TRUE | FALSE ;
number: LNK? INTEGER ( DOT frac=INTEGER )? ;
function: name=LABEL LP (arg=.)*? RP;
qualified: ( ref_schema=LABEL DOT )? name=LABEL ;
