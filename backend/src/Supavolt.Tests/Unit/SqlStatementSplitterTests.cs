using Shouldly;
using Supavolt.Api.Features.SqlEditor;

namespace Supavolt.Tests.Unit;

public class SqlStatementSplitterTests
{
    [Theory]
    [InlineData("select 1")]
    [InlineData("select 1;")]
    [InlineData("select ';'")]
    [InlineData("select 'it''s; fine'")]
    [InlineData("select 1; -- trailing comment")]
    [InlineData("select 1; -- trailing; comment with semicolons")]
    [InlineData("select 1 /* ; */")]
    [InlineData("select \"weird;name\" from t")]
    [InlineData("create function f() returns int as $$ select 1; select 2; $$ language sql")]
    [InlineData("create function f() returns int as $body$ begin; return 1; end; $body$ language plpgsql")]
    [InlineData("select $1::int, $2::int")]
    [InlineData("select a$b from t; ")]
    public void Counts_one_statement(string sql) =>
        SqlStatementSplitter.Split(sql).Count.ShouldBe(1);

    [Theory]
    [InlineData("select 1; select 2")]
    [InlineData("select ';'; select 2")]
    [InlineData("select 1 /* ; */; drop table x")]
    [InlineData("select $1; drop table x")]
    [InlineData("select $1, $2; drop table x")]   // "$1, $" is not a dollar-quote tag
    [InlineData("select 'a$b'; select $x$ ; $x$")]
    public void Counts_two_statements(string sql) =>
        SqlStatementSplitter.Split(sql).Count.ShouldBe(2);

    [Theory]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData(";;")]
    [InlineData("-- only a comment")]
    public void Counts_no_statements(string sql) =>
        SqlStatementSplitter.Split(sql).ShouldBeEmpty();

    [Fact]
    public void Trims_each_statement() =>
        SqlStatementSplitter.Split("  select 1 ;  select 2  ").ShouldBe(["select 1", "select 2"]);
}
