parser grammar kddlParser;

options { tokenVocab = kddlLexer; }

database: DATABASE name=LABEL LC ( schema | link | option ) * RC ;
schema: SCHEMA name=LABEL LC ( table | link )* RC ;
table: TABLE name=LABEL ( FS par=qualified direction? )? ( LC field* RC )?;
direction: LP ( UP | DOWN | LEFT | RIGHT ) RP;
link: left=qualified ( left_optional=QM )? ( left_mult=ST | left_single=LA )? MN+ ( right_mult=ST | right_single=RA )? right=qualified ( right_optional=QM )? CASCADE? direction? ;
field: ( pk=ST | unique=EM | indexed=PL )? name=identifier (type ( optional=QM )? default? | default | MN+ RA reference=qualified ( optional=QM )? CASCADE? direction? ) ;
identifier: LABEL | BOOLEAN | BIGINT | INT | SMALLINT | SERIAL | LONG | FLOAT | DOUBLE
          | MONEY | NUMERIC | TIME | TIMETZ | DATE | TIMESTAMP | TIMESTAMPTZ | INTERVAL
          | CHAR | VARCHAR | TEXT | BLOB | ENUM | UUID | JSON | VARBIT ;
type: BOOLEAN
    | BIGINT
    | INT
    | SMALLINT
    | SERIAL
    | LONG
    | FLOAT
    | DOUBLE
    | MONEY
    | NUMERIC ( LP prec=INTEGER ( CM scale=INTEGER )? RP )?
    | TIME
    | TIMETZ
    | DATE
    | TIMESTAMP ( LP prec=INTEGER RP )?
    | TIMESTAMPTZ ( LP prec=INTEGER RP )?
    | INTERVAL
    | CHAR ( LP width=INTEGER RP )?
    | VARCHAR ( LP ( width=INTEGER )? RP )?
    | TEXT
    | BLOB
    | ENUM LP value=STRING ( CM? value=STRING )* RP
    | UUID
    | JSON
    | VARBIT ( LP ( width=INTEGER )? RP )? ;
default: EQ expression ;
expression: NULL | boolean | number | STRING | function ;
boolean: TRUE | FALSE ;
number: MN? INTEGER ( DOT frac=INTEGER )? ;
// function: name=LABEL LP (arg=[^\\)]*)? RP; Parsing problem - CB TODO
function: name=LABEL LP arglist? RP;
qualified: ( ref_schema=LABEL DOT )? name=LABEL ;
arglist: label_or_expr ( CM label_or_expr )* ;
label_or_expr: label=LABEL | expr=expression ;
option: OPTION name=LABEL EQ value=STRING ;
