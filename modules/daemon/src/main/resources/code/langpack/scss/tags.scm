; SCSS/Sass definitions and relations. Indented Sass and plain CSS are intentionally not packs.

(declaration (property_name) @def.name) @def.variable
(mixin_statement name: (identifier) @def.name) @def.mixin
(function_statement name: (identifier) @def.name) @def.function
(rule_set (selectors) @def.name) @def.selector

(use_statement (_) @ref.name) @ref.import
(forward_statement (_) @ref.name) @ref.import
(import_statement (_) @ref.name) @ref.import
(include_statement (identifier) @ref.name) @ref.include
(call_expression (function_name) @ref.name) @ref.call
(extend_statement (_) @ref.name) @ref.extend
