import clsx from 'clsx';
import Link from '@docusaurus/Link';
import Layout from '@theme/Layout';
import HomepageFeatures from '@site/src/components/HomepageFeatures';
import ThemedImage from '@theme/ThemedImage';

import styles from './index.module.css';

function HomepageHeader() {
  return (
    <header className={clsx('hero', styles.heroBanner)}>
      <div className="container">
        <ThemedImage
          className={styles.heroLogo}
          alt="Endive"
          sources={{
            light: 'img/endive-hero.png',
            dark: 'img/endive-hero-dark.png',
          }}
        />
        <p className={clsx('hero__subtitle', styles.heroSubtitle)}>
          A JVM native WebAssembly runtime
        </p>
        <div className={styles.buttons}>
          <Link
            className="button button--primary button--lg"
            to="/docs">
            Get Started
          </Link>
          <Link
            className="button button--outline button--lg"
            to="https://github.com/bytecodealliance/endive"
            style={{marginLeft: '1rem'}}>
            GitHub
          </Link>
        </div>
      </div>
    </header>
  );
}

export default function Home(): JSX.Element {
  return (
    <Layout
      title="JVM native WebAssembly runtime"
      description="Endive is a JVM native WebAssembly runtime with zero native dependencies.">
      <HomepageHeader />
      <main>
        <HomepageFeatures />
      </main>
    </Layout>
  );
}
